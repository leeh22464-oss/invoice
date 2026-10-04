import { spawn, execFileSync } from 'node:child_process'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const backend = path.resolve(here, '../backend')
const frontend = path.resolve(here, '../frontend')
const storageRoot = path.resolve(here, '.storage')
const container = 'invoice-e2e-pg'
const children = []

function dockerBinary() {
  const candidates = [
    process.env.DOCKER,
    'docker',
    'C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe',
  ].filter(Boolean)
  for (const candidate of candidates) {
    try {
      execFileSync(candidate, ['version'], { stdio: 'ignore' })
      return candidate
    } catch {
      // Try the next candidate.
    }
  }
  throw new Error('Docker CLI was not found')
}

function waitForPort(port, timeoutMs) {
  const started = Date.now()
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const socket = net.connect({ port, host: '127.0.0.1' })
      socket.once('connect', () => {
        socket.end()
        resolve()
      })
      socket.once('error', () => {
        socket.destroy()
        if (Date.now() - started > timeoutMs) {
          reject(new Error(`Timed out waiting for 127.0.0.1:${port}`))
          return
        }
        setTimeout(attempt, 500)
      })
    }
    attempt()
  })
}

function track(child) {
  children.push(child)
  return child
}

function stopAll() {
  for (const child of children) {
    if (child.pid == null) {
      continue
    }
    try {
      if (process.platform === 'win32') {
        execFileSync('taskkill', ['/pid', String(child.pid), '/t', '/f'], { stdio: 'ignore' })
      } else {
        child.kill('SIGTERM')
      }
    } catch {
      // The process has already exited.
    }
  }
  try {
    execFileSync(dockerBinary(), ['rm', '-f', container], { stdio: 'ignore' })
  } catch {
    // The database container is already gone.
  }
}

async function main() {
  fs.mkdirSync(storageRoot, { recursive: true })
  const docker = dockerBinary()
  const dockerHome = path.dirname(docker)
  if (!process.env.PATH?.includes(dockerHome)) {
    process.env.PATH = `${dockerHome}${path.delimiter}${process.env.PATH ?? ''}`
  }
  try {
    execFileSync(docker, ['rm', '-f', container], { stdio: 'ignore' })
  } catch {
    // No previous container.
  }
  execFileSync(docker, [
    'run', '-d', '--name', container,
    '-e', 'POSTGRES_USER=invoice',
    '-e', 'POSTGRES_PASSWORD=invoice',
    '-e', 'POSTGRES_DB=invoice',
    '-p', '55432:5432',
    'postgres:16-alpine',
  ], { stdio: 'inherit' })
  await waitForPort(55432, 180_000)
  let ready = false
  let lastError = null
  for (let attempt = 0; attempt < 90; attempt += 1) {
    try {
      execFileSync(docker, ['exec', container, 'pg_isready', '-U', 'invoice'], { stdio: 'ignore' })
      ready = true
      break
    } catch (error) {
      lastError = error
      await new Promise((resolve) => setTimeout(resolve, 1000))
    }
  }
  if (!ready) {
    try {
      execFileSync(docker, ['logs', '--tail', '40', container], { stdio: 'inherit' })
    } catch {
      // Logs are only a diagnostic.
    }
    throw lastError
  }

  execFileSync('gradlew.bat', ['--no-daemon', 'exportWireMock'], {
    cwd: backend,
    stdio: 'inherit',
    shell: true,
  })
  const jarDir = path.join(backend, 'build', 'wiremock')
  const jarName = fs.readdirSync(jarDir).find((name) => name.startsWith('wiremock-standalone-') && name.endsWith('.jar'))
  if (!jarName) {
    throw new Error(`WireMock jar was not found in ${jarDir}`)
  }
  track(spawn('java', [
    '-jar', path.join(jarDir, jarName),
    '--port', '4010',
    '--root-dir', path.join(here, 'wiremock'),
  ], { stdio: 'inherit' }))
  await waitForPort(4010, 30_000)

  track(spawn('gradlew.bat', ['--no-daemon', 'bootRun', '--args=--app.jwt.secure-cookie=false'], {
    cwd: backend,
    stdio: 'inherit',
    shell: true,
    env: {
      ...process.env,
      SPRING_DATASOURCE_URL: 'jdbc:postgresql://127.0.0.1:55432/invoice',
      SPRING_DATASOURCE_USERNAME: 'invoice',
      POSTGRES_PASSWORD: 'invoice',
      APP_JWT_SECRET: '0123456789abcdef0123456789abcdef',
      APP_JWT_SECURE_COOKIE: 'false',
      APP_COOKIE_SECURE: 'false',
      APP_CREDENTIAL_MASTER_KEY: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
      APP_STORAGE_ROOT: storageRoot,
      APP_PADDLE_TOKEN_URL: 'http://127.0.0.1:4010/oauth/2.0/token',
      APP_PADDLE_RECOGNIZE_URL: 'http://127.0.0.1:4010/rest/2.0/ocr/v1/multiple_invoice',
    },
  }))
  await waitForPort(8080, 240_000)

  track(spawn('npm', ['run', 'dev', '--', '--host', '127.0.0.1', '--port', '5173', '--strictPort'], {
    cwd: frontend,
    stdio: 'inherit',
    shell: true,
  }))
  await waitForPort(5173, 120_000)
  console.log('invoice e2e stack is ready')
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    stopAll()
    process.exit(0)
  })
}
process.on('exit', stopAll)

main().catch((error) => {
  console.error(error)
  stopAll()
  process.exit(1)
})
