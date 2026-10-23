# 佣金与财务结算前端接口契约

日期：2026-10-23。范围：整合后管理端与达人端实际使用的数据适配层；本次已合并既有预付后端，本契约描述的新佣金接口仍待实现。本文件是待接入契约，不代表这些端点已存在。原系统内的显式演示模式通过内存适配器隔离佣金请求；正常模式仍使用既有HTTP客户端。演示不替换登录身份，不另起导航或应用入口。

## 1. 约定与身份边界

- 管理端前缀 `/admin-api`，达人端前缀 `/kol-api`；下表路径已包含前缀。真实HTTP封装分别在两端 `src/api/http.ts`。请求函数均在 `src/api/commission/finance.ts`，DTO完整字段以相邻 `finance-types.ts` 为准。
- JSON统一 `{code,msg,data,errorCode,traceId}`。成功HTTP200、code=0，errorCode=null；错误HTTP状态与code一致。msg不作为界面文案。字段错误为 `data:[{field,message}]`，前端只按字段名映射本地化文案。
- ID、金额均为字符串；人民币固定两位；比例为四位小数的比值，例如`"0.2000"`，界面显示20%。未知金额/比例为null，不能填0。显式`"0.0000"`是有效0%。UTC时间例如`2026-10-22T09:00:00Z`。
- 分页 `{items:[],total:"0",page:1,size:20}`；page从1开始，size最大100。前端只将total转为数字用于分页，不将金额转浮点数运算。表格数据有确定的时间/id排序。
- 公司身份来自登录态。禁止接受tenantId选择公司。达人身份来自达人登录态，达人端无kolId请求参数。商务OWN使用当前负责达人范围，不能用历史商务快照替代；平台管理员禁止公司业务。跨公司/跨范围按ID读取不返回资源。
- `commission:view`只授予范围内查询；`withdrawal:review`用于审核。配置修改及最终付款为公司SUPER_ADMIN，FINANCE是STAFF岗位模板，不因此获得付款权限；导出另需`data:export`。前端按钮限制不替代服务端鉴权。
- 普通查询返回收款信息脱敏投影；审核响应亦需同样投影。完整收款快照仅本人或授权付款管理员可读，访问应审计。内部财务备注不得返回达人或普通运营/商务。文件下载权限及公司/达人范围与业务记录一致，禁止公开URL。

## 2. 当前金额与期间统计

|字段|含义|
|---|---|
|estimated|截至当前、有确认依据的累计净佣金；包含冻结、退款调整，不扣提现和预付|
|frozen|已计算但未满足释放条件|
|occupied|有效待审核、待支付申请占用；退款暂停仍保留占用|
|cumulativePaid|累计已支付提现的人民币核销额；用于当前余额，不是期间佣金付款额|
|deducted|独立预付模块已经产生的累计有效佣金抵扣结果|
|available|estimated−frozen−occupied−cumulativePaid−deducted，可为负；钱包若已净扣减，服务端不可再次扣减|
|pendingDeduction|已支付且选择抵扣的预付中尚待后续佣金冲抵部分；不在查询时再次扣款|
|paid|当前筛选期间和付款类型内，commissionPaid+prepayPaid；纯展示合计，不再整体从佣金余额扣一次|
|commissionPaid|期间内已支付提现的CNY核销额|
|prepayPaid|独立预付模块期间内所有已支付预付，包括抵扣/不抵扣两类；排除申请/待审核/驳回/撤回/待付|
|unverified|未计入金额的待核实商品行记录数量；需要警示，并可在流水定位|

期间start含、end不含。管理端日期选择器按浏览器本地日期午夜换算UTC，end为次日本地午夜（适应夏令时）；不选日期时表示全部期间。订单按订单支付时间统计有效销售，付款按实际成功支付时间统计。付款类型同时限定付款与匹配达人范围，但订单仍统计这些达人在该期间的有效销售。订单汇总在所有匹配达人范围内按orderNo全局去重，件数按销售商品行数量，不按付款笔数倍增。

预付款按公司、达人、独立模块来源唯一编号去重；多条抵扣分配不代表多次付款。查询/刷新只读已有抵扣结果，绝不推进抵扣或插入台账。不读取预付申请附件、原因、期望日期、内部审批/凭证；不新增任何预付写接口。

## 3. 管理端路径

|方法和路径|请求|返回data|权限|
|---|---|---|---|
|GET /admin-api/commission/incomes|IncomeQuery|IncomeResult|commission:view及公司/OWN范围|
|GET /admin-api/commission/incomes/{id}|期间/付款类型条件（id为达人ID）|IncomeDetail|同列表；详情与期间付款口径一致，不因列表page而归零|
|GET /admin-api/commission/withdrawals|WithdrawalQuery|Page&lt;Withdrawal&gt;|commission:view；仅佣金提现|
|GET /admin-api/commission/withdrawals/{id}|无|Withdrawal|同范围，按角色投影|
|PUT /admin-api/commission/withdrawals/{id}/review|`{decision:"APPROVE"或"REJECT",note}`|Withdrawal|withdrawal:review及OWN；待支付驳回仅授权财务/公司超管|
|PUT /admin-api/commission/withdrawals/{id}/payment|PaymentRegistration + idempotencyKey|Withdrawal|公司SUPER_ADMIN；再次核验状态、阻断、快照和占用|
|GET /admin-api/commission/withdrawals/export|已应用WithdrawalQuery筛选，不含page/size|CSV下载、Content-Disposition|commission:view + data:export；服务端完整导出当前范围，不能截断100条|
|GET /admin-api/commission/settlement-configs/current|无|SettlementConfig|commission:view|
|PUT /admin-api/commission/settlement-configs/current|SettlementConfig（含当前version）|新版本SettlementConfig|公司SUPER_ADMIN；乐观并发校验|
|GET /admin-api/commission/settlement-details|SettlementQuery|Page&lt;Settlement&gt;|commission:view及OWN|
|POST /admin-api/files|multipart file，bizType=WITHDRAWAL_VOUCHER|`{fileId:"…"}`|付款管理员；复用鉴权文件能力，后端需登记此业务类型|
|GET /admin-api/files/{id}|无|文件二进制/Content-Disposition|关联提现访问范围/凭证权限|

IncomeQuery：`keyword?,paymentType?(COMMISSION/PREPAY；空=全部),start?,end?,page?,size?`。keyword匹配达人名称、编号、邮箱。

IncomeResult：`{items:Income[],total,page,size,summary:{paid,commissionPaid,prepayPaid,creators,orders,quantity,unverified}}`。summary基于全部匹配结果，不能仅汇总当前页。

Income：`{id,name,code,email,bdId,bd,orderCount,quantity,lastPaidAt,...Metrics}`。前三个主金额独立展示；主列表不增加occupied/pendingDeduction列。lastPaidAt为当前筛选条件下最近成功支付时间。

IncomeDetail：`{income:Income,lines:CommissionLine[],payments:Payment[]}`。本前端详情读取完整演示商品流水；真实大规模接入需要后端保证有界响应或另行约定分页适配，不能静默截断。列表income.id与此路径同为达人ID。

CommissionLine：`{id,orderNo,itemId,product,sku,image,base,rate,rateVersion,ruleVersion,commission,adjustment,releaseBasis,businessAt,direction,subject,kol}`。image为鉴权可访问或已脱敏的商品图片，不能带凭证令牌。base为该次来源快照的微店商品行计佣依据；commission为本条账务变动；adjustment为退款调整（负数允许）。同商品行正向记录与负向差额保留，不覆盖历史；基数已经扣退款时不再减一次退款。rateVersion按达人及版本共同追溯，ruleVersion是历史冻结规则版本。

releaseBasis枚举：CONFIRMED（微店确认结算）、FALLBACK（缺少结算信息，以交易完成时间+历史冻结天数释放）、UNSETTLED（微店明确未结算）、AFTER_SALES（售后处理中）、UNVERIFIED（基数/比例/归属等待核实）。明确未结算不能用天数兜底，缺交易完成时间不能用付款时间替代。前端只展示服务端依据，不自行解冻或推断外部字段。

Payment：`{id,sourceNo,type,kolId,amountCny,paidAt,deduct,deducted,pending,actualAmount?,actualCurrency?,voucher?,withdrawalId?}`。
- COMMISSION：withdrawalId关联提现详情；actualAmount/actualCurrency为实际支付事实，amountCny为核销额；voucher为鉴权文件ID。
- PREPAY：只提供来源编号/CNY记账金额/支付时间/是否抵扣/已抵扣/待抵扣；不提供预付申请详情或凭证读取入口。不得将旧备注里的外币数字解析成正式实付字段。

WithdrawalQuery：`applicationNo?,kol?（姓名/编号）,type?（仅COMMISSION）,status?,page?,size?`。不合并PREPAY任务。

Withdrawal：`{id,applicationNo,kolId,kolName,kolCode,bdId,type:"COMMISSION",amount,currency:"CNY",status,createdAt,paused,pauseReason,account,note,reviewNote,financeNote,allocations,history,payment,previousId}`。
- account为申请时不可变收款快照`{id?,type:"PAYPAL"|"USDT",account,currency,network,holder,qrFileId?}`，不跟随以后账户修改。
- allocations：`[{id,orderNo,image,product,sku,occupied,remainingSnapshot}]`。remainingSnapshot为该申请分配后当时商品行剩余可提现金额，不随退款/后续申请重算。
- history：`[{action,at,actor,note}]`，保留提交、通过/驳回、撤回、付款的真实操作者及UTC时间；按角色裁剪内部备注。
- payment为PaymentRegistration或null；previousId为驳回后新申请关联的旧申请ID。

状态：PENDING_REVIEW→PENDING_PAYMENT→PAID；PENDING_REVIEW可REJECTED/WITHDRAWN；PENDING_PAYMENT可由授权财务/超管驳回。退款暂停使用paused=true+原因，保持原状态、申请金额及占用；禁止支付。PAID不得直接撤回/改写，已付后退款追加负向佣金并允许负余额。

PaymentRegistration：`{actualAmount,actualCurrency,amountCny,paidAt,method,financeNote,conversionNote,voucher}`。提交另带`idempotencyKey`。
- 系统外支付完成后才登记，无转账/自动换汇能力。
- actualCurrency必须匹配收款快照（PayPal支持既有USD/EUR/GBP/CNY，USDT单独币种且保留网络）；当前表单实际金额按两位小数正数校验，未来若后端确认其他币种精度须同步表单与契约，不能自行换算。
- amountCny必须等于批准的amount，差异驳回后重新申请。非CNY支付conversionNote必填，CNY可空。paidAt提交后规范化UTC。凭证必填，当前前端接受PNG/JPEG/WebP/PDF，最大10MB；后端必须校验真实文件头、公司及业务归属。
- 同申请只允许一次成功付款。同幂等键同内容重试返回原结果，不得再次核销；同键不同内容409。超时后先查询结果，不要求再次线下支付。

SettlementQuery：`orderNo?,channel?,shop?,page?,size?`。Settlement：`{id,orderNo,channel,shop,kolNames[],payment,refund,net,commission,rates[],retained,status,businessAt,lines:CommissionLine[],scopeLimited?}`。
- payment与成功refund分开；net为订单账务增加减扣回净额，后端权威返回，不用申请退款额替代、不由前端假定等于支付减退款。
- retained为确认计佣基数净额减达人佣金，仅分佣后留存，未扣成本/税费。
- 多达人/多比例以集合返回，列表显示“多个”，详情按商品行展开。
- status仅POSTED或UNVERIFIED等账务状态，不使用提现PAID代替。
- OWN遇多达人订单时只返回范围内商品行。不能安全归属的整单金额返回null+scopeLimited=true，界面解释当前权限不可见，不能冒充零金额或外部字段待核实，也不能泄露其他达人。服务端不得因为混单而静默丢掉本人商品行。

SettlementConfig：`{currency:"CNY",minimum:"200.00",version:"…",channels:[{code:"WEIDIAN",name:"weidian",basis:"freezeBasis",days:7}]}`。name/basis当前为既定枚举对应前端语言键。minimum为非负CNY；演示冻结天数可配置0–365整数。保存携带原version，成功返回新version；修改只用于新付款订单，历史快照不变。新后端需保存版本及审计。

## 4. 达人端路径

|方法和路径|请求|返回data|权限|
|---|---|---|---|
|GET /kol-api/commissions/overview|当前前端传{}；可沿IncomeQuery期间参数扩展，禁止kolId|IncomeDetail本人投影|达人登录态本人|
|GET /kol-api/withdrawals/conditions|无|SettlementConfig（仅本人提现所需公开条件）|本人|
|GET /kol-api/withdrawals/recipient-options|无|AccountSnapshot[]，含已审核本人可用账号ID/类型/币种/网络/二维码引用|本人；复用账户服务，无他人资料|
|GET /kol-api/withdrawals|page,size|Page&lt;Withdrawal&gt;本人投影|本人|
|GET /kol-api/withdrawals/{id}|无|Withdrawal本人投影|本人|
|POST /kol-api/withdrawals|WithdrawalRequest|新Withdrawal|本人|
|PUT /kol-api/withdrawals/{id}/withdraw|{}|Withdrawal|本人且待审核|
|POST /kol-api/files|multipart file,bizType=PAYOUT_QR|`{fileId:"…"}`|本人；复用现有文件能力|
|GET /kol-api/files/{id}|无|文件二进制|本人关联提现的付款凭证|

WithdrawalRequest：`{amount:"200.00",account:AccountSnapshot,note:"…",previousId?,payoutAccountId?,idempotencyKey:"UUID"}`。

- 不传kolId/tenantId。选择本人已审核账户时传payoutAccountId，后端验证归属、状态、币种/网络并按服务端权威内容生成快照，不能信任客户端改动。手填则验证PayPal邮箱/姓名/受支持币种，或USDT账号/网络/姓名及可选二维码，并绑定本次申请，不新增预付或替他人开户。
- 前端USDT沿用现有账号规则：字母数字3–128字符，网络TRC20/ERC20/BEP20/SOL/TON；二维码沿用现有PNG/JPEG、最多2MB。已审核账户快照控件不可改；要填写另一组资料切手填。
- amount为正数CNY、最多两位、达到minimum且不超available。前端提示门槛、冻结/占用/预付抵扣及资料缺失；后端锁钱包及商品行，原子校验、分配占用、创建唯一申请并保存收款/分配后剩余快照。
- 撤回只允许待审核，释放一次占用；驳回后可修改重新提交，创建新申请并保留previousId，不覆盖旧审批史。已付不能撤回。
- 本人付款结果包含实际金额/币种、CNY核销、支付时间/方式/凭证及审核结果；不返回内部财务备注。预付仅本人已支付金额/抵扣结果投影，不扩大独立预付模块内部资料可见范围。

## 5. 示例

```json
{"code":0,"msg":"Success","data":{"items":[{"id":"102301","name":"t1023_Alex Morgan","code":"t1023_K001","email":"t1023_alex@example.com","bdId":"102311","bd":"t1023_Jordan","orderCount":3,"quantity":6,"estimated":"2600.00","frozen":"400.00","available":"600.00","occupied":"600.00","cumulativePaid":"700.00","deducted":"300.00","pendingDeduction":"200.00","paid":"1320.00","commissionPaid":"700.00","prepayPaid":"620.00","lastPaidAt":"2026-10-22T09:00:00Z","unverified":1}],"total":"1","page":1,"size":20,"summary":{"paid":"1320.00","commissionPaid":"700.00","prepayPaid":"620.00","creators":1,"orders":3,"quantity":6,"unverified":1}},"errorCode":null,"traceId":"t1023_example"}
```

```json
{"code":409,"msg":"Conflicts with existing data","data":null,"errorCode":"COMMON_CONFLICT","traceId":"t1023_example"}
```

## 6. 错误与待接入事项

复用已登记错误：400 COMMON_VALIDATION_FAILED（字段错误映射）、401 AUTH_UNAUTHORIZED（现有拦截器）、403 AUTH_FORBIDDEN、404 COMMON_NOT_FOUND、409 COMMON_CONFLICT（状态、重复付款、幂等键内容冲突、配置版本冲突）、500 COMMON_INTERNAL_ERROR；隔离适配器使用503+COMMON_INTERNAL_ERROR模拟服务故障，生产应按真实故障类别返回规范状态。新增业务错误码应在后端shared.ErrorCode和两端语言包统一登记，本次未修改后端枚举。

待后端重新授权后实现：真实外部商品行基数/历史比例/冻结依据验证；接口与事务/锁/幂等持久化；独立预付公开API投影及FIFO既有抵扣结果衔接；权限和脱敏/文件鉴权；账户有效性与停用保护；通知/审计；真实数据闭环和OpenAPI。不得从本前端演示推断这些已完成。
