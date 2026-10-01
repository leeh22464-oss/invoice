const STATUS_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  OCR_PROCESSING: '识别中',
  REVIEW_REQUIRED: '待复核',
  PASSED: '已通过',
  BLOCKED_DUPLICATE: '已拦截',
  FAILED: '处理失败',
  PROCESSING: '处理中',
  COMPLETED: '已完成',
}

const TYPE_LABELS: Record<string, string> = {
  VAT_SPECIAL: '增值税专用发票',
  VAT_ORDINARY: '增值税普通发票',
  ELECTRONIC: '电子发票',
  RAILWAY: '火车票',
  FLIGHT: '机票行程单',
  TAXI: '出租车票',
  FIXED_AMOUNT: '定额发票',
  CUSTOMS_PAYMENT: '海关缴款书',
  UNKNOWN: '未知票种',
}

const FIELD_LABELS: Record<string, string> = {
  invoiceCode: '发票代码',
  invoiceNumber: '发票号码',
  invoiceDate: '开票日期',
  amount: '金额',
  tax: '税额',
  total: '价税合计',
  checkCode: '校验码',
  sellerName: '销售方',
  sellerTaxId: '销售方税号',
  buyerName: '购买方',
  buyerTaxId: '购买方税号',
  passenger: '乘客',
  trainNumber: '车次',
  flightNumber: '航班号',
  ticketNumber: '客票号码',
}

export function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status
}

export function invoiceTypeLabel(type: string): string {
  return TYPE_LABELS[type] ?? type
}

export function fieldLabel(name: string): string {
  return FIELD_LABELS[name] ?? name
}

export const INVOICE_TYPES = Object.keys(TYPE_LABELS)
