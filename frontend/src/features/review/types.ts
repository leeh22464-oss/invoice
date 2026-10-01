export type VerificationIssue = {
  code: string
  field: string
  message: string
  severity: string
}

export type DuplicateEvidence = {
  matchType: string
  matchedInvoiceId: string
  score: number
  matchedFields: string[]
}

export type InvoiceRecord = {
  id: string
  invoiceType: string
  invoiceCode: string | null
  invoiceNumber: string | null
  invoiceDate: string | null
  amount: string | number | null
  tax: string | number | null
  total: string | number | null
  status: string
  fields: Record<string, string>
  createdAt: string
  updatedAt: string
  fieldConfidence?: Record<string, number>
  issues?: VerificationIssue[]
  duplicateEvidence?: DuplicateEvidence | null
}

export function approvalBlocked(invoice: InvoiceRecord): boolean {
  return invoice.status === 'BLOCKED_DUPLICATE' || invoice.duplicateEvidence?.matchType === 'EXACT'
}
