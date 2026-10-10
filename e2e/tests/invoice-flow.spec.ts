import { expect, test, type Page } from '@playwright/test'

const password = 'StrongPass1!'

const jpeg = {
  name: 'special.jpg',
  mimeType: 'image/jpeg',
  buffer: Buffer.from([0xff, 0xd8, 0xff, 0x00, 0x00, 0x00, 0x00, 0x00]),
}

const png = {
  name: 'special-dup.png',
  mimeType: 'image/png',
  buffer: Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x00]),
}

test('invoice workflow covers review, duplicate block, export, and cross-user denial', async ({ browser }) => {
  const ownerContext = await browser.newContext()
  const owner = await ownerContext.newPage()
  const ownerEmail = `owner-${Date.now()}@example.com`

  const anonymous = owner.waitForResponse(
    (response) => response.url().includes('/api/auth/me') && response.status() === 401,
  )
  await owner.goto('/register')
  await anonymous
  const csrf = (await ownerContext.cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')
  expect(csrf?.value).toBeTruthy()

  const credentialLoad = owner.waitForResponse(
    (response) => response.url().includes('/api/settings/ocr-credentials') && response.request().method() === 'GET',
  )
  await register(owner, ownerEmail)
  await credentialLoad
  await expect.poll(async () => owner.evaluate(() => document.cookie)).toContain('XSRF-TOKEN=')
  await expect.poll(async () => ownerContext.cookies()).toEqual(expect.arrayContaining([
    expect.objectContaining({ name: 'XSRF-TOKEN', secure: false }),
  ]))
  await owner.getByLabel('接口地址').fill('http://127.0.0.1:4010/ocr')
  await owner.getByLabel('访问令牌').fill('e2e-secret-key')
  await owner.getByRole('button', { name: '保存' }).click()
  await expect(owner.getByText('http****/ocr')).toBeVisible()

  await owner.getByRole('link', { name: '导入' }).click()
  await owner.locator('#import-files').setInputFiles([jpeg, png])
  const uploaded = owner.waitForResponse(
    (response) => response.url().includes('/api/import-batches') && response.request().method() === 'POST',
  )
  await owner.getByRole('button', { name: '上传' }).click()
  const uploadResponse = await uploaded
  expect(uploadResponse.status()).toBe(201)
  const batch = await uploadResponse.json() as { id: string }
  expect(batch.id).toMatch(/^[0-9a-f-]{36}$/i)

  await owner.getByRole('link', { name: '复核' }).click()
  await expect(owner.locator('[data-field="InvoiceNum"] input, [data-field="invoiceNumber"] input').first()).toHaveValue('00123456')
  await expect(owner.getByText('已拦截')).toBeVisible()

  const invoiceResponse = await owner.request.get('/api/invoices')
  expect(invoiceResponse.status()).toBe(200)
  const invoices = await invoiceResponse.json() as Array<{ id: string, status: string }>
  const blocked = invoices.find((invoice) => invoice.status === 'BLOCKED_DUPLICATE')
  const passed = invoices.find((invoice) => invoice.status === 'PASSED')
  expect(blocked?.id).toBeTruthy()
  expect(passed?.id).toBeTruthy()

  await owner.getByRole('link', { name: '台账' }).click()
  await expect(owner.getByRole('link', { name: '导出 CSV' })).toHaveAttribute('href', /\/api\/ledger\/export\.csv/)
  const exportResponse = await owner.request.get('/api/ledger/export.csv')
  expect(exportResponse.status()).toBe(200)
  expect(await exportResponse.text()).toContain('00123456')

  const otherContext = await browser.newContext()
  const other = await otherContext.newPage()
  await other.goto('/register')
  await register(other, `other-${Date.now()}@example.com`)

  expect((await other.request.get(`/api/invoices/${blocked!.id}`)).status()).toBe(404)
  expect((await other.request.get(`/api/import-batches/${batch.id}`)).status()).toBe(404)
  expect((await other.request.get(`/api/invoices/${blocked!.id}/file`)).status()).toBe(404)

  await otherContext.close()
  await ownerContext.close()
})

async function register(page: Page, email: string) {
  await page.getByLabel('邮箱').fill(email)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '注册' }).click()
  await expect(page.getByRole('heading', { name: 'OCR 凭证' })).toBeVisible()
}
