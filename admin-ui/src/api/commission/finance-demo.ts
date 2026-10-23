import type {
  AccountSnapshot,
  CommissionLine,
  Income,
  Payment,
  Settlement,
  SettlementConfig,
  Withdrawal
} from "./finance-types";

// Synthetic preview records. No external field names or real financial records are inferred.
export const demoImage =
  "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='56' height='56'%3E%3Crect width='56' height='56' rx='8' fill='%23edf2fc'/%3E%3Cpath d='M18 17h20v26H18zM23 17v-4h10v4' fill='none' stroke='%232457d6' stroke-width='2'/%3E%3C/svg%3E";
export const account: AccountSnapshot = {
  type: "PAYPAL",
  account: "t1023_creator@example.com",
  currency: "USD",
  network: "",
  holder: "t1023_Alex"
};
export const seedIncomes: Income[] = [
  {
    id: "102301",
    name: "t1023_Alex Morgan",
    code: "t1023_K001",
    email: "t1023_alex@example.com",
    bdId: "102311",
    bd: "t1023_Jordan",
    orderCount: 3,
    quantity: 6,
    estimated: "2600.00",
    frozen: "400.00",
    available: "600.00",
    occupied: "600.00",
    cumulativePaid: "700.00",
    deducted: "300.00",
    pendingDeduction: "200.00",
    paid: "0.00",
    commissionPaid: "0.00",
    prepayPaid: "0.00",
    lastPaidAt: null,
    unverified: 1
  },
  {
    id: "102302",
    name: "t1023_Emma Lee",
    code: "t1023_K002",
    email: "t1023_emma@example.com",
    bdId: "102312",
    bd: "t1023_Taylor",
    orderCount: 1,
    quantity: 2,
    estimated: "360.00",
    frozen: "100.00",
    available: "-40.00",
    occupied: "0.00",
    cumulativePaid: "300.00",
    deducted: "0.00",
    pendingDeduction: "0.00",
    paid: "0.00",
    commissionPaid: "0.00",
    prepayPaid: "0.00",
    lastPaidAt: null,
    unverified: 0
  },
  {
    id: "102303",
    name: "t1023_Sam Rivera",
    code: "t1023_K003",
    email: "t1023_sam@example.com",
    bdId: "102311",
    bd: "t1023_Jordan",
    orderCount: 1,
    quantity: 1,
    estimated: "0.00",
    frozen: "0.00",
    available: "0.00",
    occupied: "0.00",
    cumulativePaid: "0.00",
    deducted: "0.00",
    pendingDeduction: "0.00",
    paid: "0.00",
    commissionPaid: "0.00",
    prepayPaid: "0.00",
    lastPaidAt: null,
    unverified: 2
  }
];
export const seedPayments: Payment[] = [
  {
    id: "102341",
    sourceNo: "t1023_W004",
    withdrawalId: "102374",
    type: "COMMISSION",
    kolId: "102301",
    amountCny: "700.00",
    paidAt: "2026-10-20T09:00:00Z",
    deduct: false,
    deducted: "0.00",
    pending: "0.00",
    actualAmount: "100.00",
    actualCurrency: "USD",
    voucher: "t1023_voucher_004"
  },
  {
    id: "102342",
    sourceNo: "t1023_P001",
    type: "PREPAY",
    kolId: "102301",
    amountCny: "500.00",
    paidAt: "2026-10-21T09:00:00Z",
    deduct: true,
    deducted: "300.00",
    pending: "200.00"
  },
  {
    id: "102343",
    sourceNo: "t1023_P002",
    type: "PREPAY",
    kolId: "102301",
    amountCny: "120.00",
    paidAt: "2026-10-22T09:00:00Z",
    deduct: false,
    deducted: "0.00",
    pending: "0.00"
  },
  {
    id: "102344",
    sourceNo: "t1023_P003",
    type: "PREPAY",
    kolId: "102302",
    amountCny: "80.00",
    paidAt: "2026-10-10T09:00:00Z",
    deduct: false,
    deducted: "0.00",
    pending: "0.00"
  }
];
export const seedLines: CommissionLine[] = [
  {
    id: "102351",
    orderNo: "t1023_WD001",
    itemId: "102361",
    product: "t1023_Canvas Tote",
    sku: "TOTE-NAVY",
    image: demoImage,
    base: "13000.00",
    rate: "0.2000",
    rateVersion: "RATE-2026-09-V2",
    ruleVersion: "WD-2026-10-V1",
    commission: "2600.00",
    adjustment: "0.00",
    releaseBasis: "CONFIRMED",
    businessAt: "2026-10-12T08:00:00Z",
    direction: "CREDIT",
    subject: "KOL",
    kol: "t1023_Alex Morgan"
  },
  {
    id: "102352",
    orderNo: "t1023_WD002",
    itemId: "102362",
    product: "t1023_Travel Pouch",
    sku: "POUCH-SAND",
    image: demoImage,
    base: "2000.00",
    rate: "0.2000",
    rateVersion: "RATE-2026-09-V2",
    ruleVersion: "WD-2026-10-V1",
    commission: "400.00",
    adjustment: "0.00",
    releaseBasis: "UNSETTLED",
    businessAt: "2026-10-18T08:00:00Z",
    direction: "CREDIT",
    subject: "KOL",
    kol: "t1023_Alex Morgan"
  },
  {
    id: "102353",
    orderNo: "t1023_WD001",
    itemId: "102361",
    product: "t1023_Canvas Tote",
    sku: "TOTE-NAVY",
    image: demoImage,
    base: "11000.00",
    rate: "0.2000",
    rateVersion: "RATE-2026-09-V2",
    ruleVersion: "WD-2026-10-V1",
    commission: "-400.00",
    adjustment: "-400.00",
    releaseBasis: "CONFIRMED",
    businessAt: "2026-10-19T08:00:00Z",
    direction: "DEBIT",
    subject: "KOL",
    kol: "t1023_Alex Morgan"
  },
  {
    id: "102354",
    orderNo: "t1023_WD003",
    itemId: "102363",
    product: "t1023_Weekend Bag",
    sku: "BAG-BLACK",
    image: demoImage,
    base: null,
    rate: null,
    rateVersion: null,
    ruleVersion: "WD-2026-10-V1",
    commission: null,
    adjustment: null,
    releaseBasis: "UNVERIFIED",
    businessAt: "2026-10-20T08:00:00Z",
    direction: "CREDIT",
    subject: "KOL",
    kol: "t1023_Alex Morgan"
  }
];
seedLines.push(
  {
    ...seedLines[1],
    id: "102355",
    itemId: "102364",
    product: "t1023_Key Case",
    base: "1000.00",
    rate: "0.1000",
    rateVersion: "RATE-EMMA-2026-09-V1",
    commission: "100.00",
    kol: "t1023_Emma Lee"
  },
  {
    ...seedLines[0],
    id: "102356",
    orderNo: "t1023_WD004",
    itemId: "102365",
    product: "t1023_Travel Kit",
    base: "3000.00",
    rate: "0.1000",
    rateVersion: "RATE-EMMA-2026-09-V1",
    commission: "300.00",
    kol: "t1023_Emma Lee",
    releaseBasis: "FALLBACK"
  },
  {
    ...seedLines[2],
    id: "102357",
    orderNo: "t1023_WD004",
    itemId: "102365",
    product: "t1023_Travel Kit",
    base: "2600.00",
    rate: "0.1000",
    rateVersion: "RATE-EMMA-2026-09-V1",
    commission: "-40.00",
    adjustment: "-40.00",
    kol: "t1023_Emma Lee",
    releaseBasis: "FALLBACK"
  },
  {
    ...seedLines[3],
    id: "102358",
    orderNo: "t1023_WD005",
    itemId: "102366",
    product: "t1023_Card Wallet",
    kol: "t1023_Sam Rivera"
  },
  {
    ...seedLines[3],
    id: "102359",
    orderNo: "t1023_WD005",
    itemId: "102367",
    product: "t1023_Card Wallet Limited",
    kol: "t1023_Sam Rivera",
    base: "100.00"
  },
  {
    ...seedLines[0],
    id: "102360",
    orderNo: "t1023_WD005",
    itemId: "102368",
    product: "t1023_Bonus Sticker",
    kol: "t1023_Sam Rivera",
    base: "100.00",
    rate: "0.0000",
    rateVersion: "RATE-SAM-2026-10-V1",
    commission: "0.00",
    adjustment: "0.00"
  }
);
seedPayments.push({
  id: "102345",
  sourceNo: "t1023_W008",
  withdrawalId: "102378",
  type: "COMMISSION",
  kolId: "102302",
  amountCny: "300.00",
  paidAt: "2026-09-20T09:00:00Z",
  deduct: false,
  deducted: "0.00",
  pending: "0.00",
  actualAmount: "42.86",
  actualCurrency: "USD",
  voucher: "t1023_voucher_007"
});
export const sales = [
  { orderNo: "t1023_WD001", kolId: "102301", paidAt: "2026-10-12T08:00:00Z", quantity: 3 },
  { orderNo: "t1023_WD002", kolId: "102301", paidAt: "2026-10-18T08:00:00Z", quantity: 2 },
  { orderNo: "t1023_WD003", kolId: "102301", paidAt: "2026-10-20T08:00:00Z", quantity: 1 },
  { orderNo: "t1023_WD002", kolId: "102302", paidAt: "2026-10-18T08:00:00Z", quantity: 1 },
  { orderNo: "t1023_WD004", kolId: "102302", paidAt: "2026-10-10T08:00:00Z", quantity: 2 },
  { orderNo: "t1023_WD005", kolId: "102303", paidAt: "2026-10-14T08:00:00Z", quantity: 3 }
];
export const seedWithdrawals: Withdrawal[] = [
  "PENDING_REVIEW",
  "PENDING_FINANCE_REVIEW",
  "PENDING_PAYMENT",
  "PENDING_PAYMENT",
  "PAID",
  "REJECTED",
  "WITHDRAWN"
].map((status, index) => ({
  id: String(102371 + index),
  applicationNo: `t1023_W00${index + 1}`,
  kolId: "102301",
  kolName: "t1023_Alex Morgan",
  kolCode: "t1023_K001",
  bdId: "102311",
  bdName: "t1023_Jordan",
  type: "COMMISSION",
  amount: index === 4 ? "700.00" : "200.00",
  estimatedTotal: "2600.00",
  availableTotal: "600.00",
  currency: "CNY",
  status: status as Withdrawal["status"],
  createdAt: `2026-10-${String(12 + index).padStart(2, "0")}T08:00:00Z`,
  paused: index === 3,
  pauseReason: index === 3 ? "refundPause" : null,
  account: structuredClone(account),
  note: "t1023_application_note",
  reviewNote: index === 5 ? "rejectExample" : "",
  financeNote: "",
  allocations:
    index === 0
      ? Array.from({ length: 12 }, (_, allocationIndex) => {
          const orderIndex = allocationIndex < 2 ? 1 : allocationIndex;
          return {
            id: String(102381 + allocationIndex),
            orderNo: `t1023_WD${String(orderIndex).padStart(3, "0")}`,
            image: demoImage,
            product: allocationIndex === 1 ? "t1023_Canvas Tote Gift Set" : "t1023_Canvas Tote",
            sku: allocationIndex === 1 ? "TOTE-GIFT-NAVY" : "TOTE-NAVY",
            occupied: allocationIndex < 2 ? "20.00" : "16.00",
            remainingSnapshot: "900.00",
            orderRemainingSnapshot: orderIndex === 1 ? "125.00" : "0.00"
          };
        })
      : [
          {
            id: String(102381 + index),
            orderNo: "t1023_WD001",
            image: demoImage,
            product: "t1023_Canvas Tote",
            sku: "TOTE-NAVY",
            occupied: index === 4 ? "700.00" : "200.00",
            remainingSnapshot: "900.00",
            orderRemainingSnapshot: "900.00"
          }
        ],
  history:
    status === "PENDING_FINANCE_REVIEW"
      ? [
          {
            action: "SUBMITTED",
            at: "2026-10-18T08:00:00Z",
            actor: "t1023_Alex",
            actorId: "102301",
            note: ""
          },
          {
            action: "PENDING_FINANCE_REVIEW",
            at: "2026-10-19T08:00:00Z",
            actor: "t1023_Operations Demo",
            actorId: "t1023_operations",
            note: ""
          }
        ]
      : status === "PENDING_PAYMENT" || status === "PAID"
        ? [
            {
              action: "SUBMITTED",
              at: "2026-10-18T08:00:00Z",
              actor: "t1023_Alex",
              actorId: "102301",
              note: ""
            },
            {
              action: "PENDING_FINANCE_REVIEW",
              at: "2026-10-19T08:00:00Z",
              actor: "t1023_Operations Demo",
              actorId: "t1023_operations",
              note: ""
            },
            {
              action: "PENDING_PAYMENT",
              at: "2026-10-20T08:00:00Z",
              actor: "t1023_Finance Demo",
              actorId: "t1023_finance",
              note: ""
            }
          ]
        : [
            {
              action: "SUBMITTED",
              at: "2026-10-18T08:00:00Z",
              actor: "t1023_Alex",
              actorId: "102301",
              note: ""
            }
          ],
  payment:
    index === 4
      ? {
          actualAmount: "100.00",
          actualCurrency: "USD",
          amountCny: "700.00",
          paidAt: "2026-10-20T09:00:00Z",
          method: "PAYPAL",
          financeNote: "t1023_payment_note",
          conversionNote: "t1023_700 CNY = 100 USD, manual confirmation",
          voucher: "t1023_voucher_004"
        }
      : null,
  previousId: null
}));
export const seedSettlements: Settlement[] = [
  {
    id: "102391",
    orderNo: "t1023_WD001",
    channel: "WEIDIAN",
    shop: "t1023_Studio",
    kolNames: ["t1023_Alex Morgan"],
    payment: "15000.00",
    refund: "2000.00",
    net: "11000.00",
    commission: "2200.00",
    rates: ["0.2000"],
    retained: "8800.00",
    status: "POSTED",
    businessAt: "2026-10-19T08:00:00Z",
    lines: [seedLines[0], seedLines[2]]
  },
  {
    id: "102392",
    orderNo: "t1023_WD002",
    channel: "WEIDIAN",
    shop: "t1023_Studio",
    kolNames: ["t1023_Alex Morgan", "t1023_Emma Lee"],
    payment: "4000.00",
    refund: "0.00",
    net: "3000.00",
    commission: "500.00",
    rates: ["0.2000", "0.1000"],
    retained: "2500.00",
    status: "POSTED",
    businessAt: "2026-10-18T08:00:00Z",
    lines: [seedLines[1], seedLines[4]]
  },
  {
    id: "102393",
    orderNo: "t1023_WD003",
    channel: "WEIDIAN",
    shop: "t1023_Studio",
    kolNames: ["t1023_Alex Morgan"],
    payment: "1500.00",
    refund: "0.00",
    net: null,
    commission: null,
    rates: [],
    retained: null,
    status: "UNVERIFIED",
    businessAt: "2026-10-20T08:00:00Z",
    lines: [seedLines[3]]
  }
];
seedSettlements.push(
  {
    id: "102394",
    orderNo: "t1023_WD004",
    channel: "WEIDIAN",
    shop: "t1023_Studio",
    kolNames: ["t1023_Emma Lee"],
    payment: "3500.00",
    refund: "400.00",
    net: "2600.00",
    commission: "260.00",
    rates: ["0.1000"],
    retained: "2340.00",
    status: "POSTED",
    businessAt: "2026-10-19T08:00:00Z",
    lines: [seedLines[5], seedLines[6]]
  },
  {
    id: "102395",
    orderNo: "t1023_WD005",
    channel: "WEIDIAN",
    shop: "t1023_Studio",
    kolNames: ["t1023_Sam Rivera"],
    payment: "500.00",
    refund: "0.00",
    net: null,
    commission: null,
    rates: ["0.0000"],
    retained: null,
    status: "UNVERIFIED",
    businessAt: "2026-10-20T08:00:00Z",
    lines: [seedLines[7], seedLines[8], seedLines[9]]
  }
);
seedWithdrawals.push({
  ...structuredClone(seedWithdrawals[4]),
  id: "102378",
  applicationNo: "t1023_W008",
  kolId: "102302",
  kolName: "t1023_Emma Lee",
  kolCode: "t1023_K002",
  bdId: "102312",
  bdName: "t1023_Taylor",
  amount: "300.00",
  estimatedTotal: "360.00",
  availableTotal: "-40.00",
  createdAt: "2026-09-15T08:00:00Z",
  account: {
    type: "PAYPAL",
    account: "t1023_emma@example.com",
    holder: "t1023_Emma",
    currency: "USD",
    network: ""
  },
  allocations: [
    {
      id: "102387",
      orderNo: "t1023_WD004",
      image: demoImage,
      product: "t1023_Travel Kit",
      sku: "KIT-SAND",
      occupied: "300.00",
      remainingSnapshot: "0.00",
      orderRemainingSnapshot: "0.00"
    }
  ],
  history: [
    {
      action: "SUBMITTED",
      at: "2026-09-15T08:00:00Z",
      actor: "t1023_Emma",
      actorId: "102302",
      note: ""
    },
    {
      action: "PENDING_FINANCE_REVIEW",
      at: "2026-09-16T08:00:00Z",
      actor: "t1023_Operations Demo",
      actorId: "t1023_operations",
      note: ""
    },
    {
      action: "PENDING_PAYMENT",
      at: "2026-09-17T08:00:00Z",
      actor: "t1023_Finance Demo",
      actorId: "t1023_finance",
      note: ""
    }
  ],
  payment: {
    actualAmount: "42.86",
    actualCurrency: "USD",
    amountCny: "300.00",
    paidAt: "2026-09-20T09:00:00Z",
    method: "PAYPAL",
    financeNote: "t1023_payment_note",
    conversionNote: "t1023_300CNY_to_42.86USD",
    voucher: "t1023_voucher_007"
  }
});
for (const row of seedWithdrawals) {
  if (
    row.status !== "PENDING_REVIEW" &&
    row.status !== "WITHDRAWN" &&
    !row.history.some(
      event =>
        event.action === row.status || (row.status === "PAID" && event.action === "PENDING_PAYMENT")
    )
  )
    row.history.push({
      action: row.status === "REJECTED" ? "REJECTED" : "PENDING_PAYMENT",
      at: new Date(Date.parse(row.createdAt) + 3600000).toISOString(),
      actor: "t1023_BD",
      note: row.reviewNote
    });
  if (row.payment)
    row.history.push({
      action: "PAID",
      at: row.payment.paidAt,
      actor: "t1023_SUPER_ADMIN",
      note: row.payment.financeNote
    });
  if (row.status === "WITHDRAWN")
    row.history.push({
      action: "WITHDRAWN",
      at: "2026-10-20T09:00:00Z",
      actor: "t1023_Alex",
      note: ""
    });
  row.history[0].at = row.createdAt;
}
export const seedConfig: SettlementConfig = {
  currency: "CNY",
  minimum: "200.00",
  version: "CFG-2026-10-V1",
  channels: [{ code: "WEIDIAN", name: "weidian", basis: "freezeBasis", days: 7 }]
};
