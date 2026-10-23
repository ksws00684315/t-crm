function onOpen() {
  SpreadsheetApp.getUi()
    .createMenu("链接工具")
	.addItem("🔗 一键替换链接(V2)", "replacementChannelLink")
    .addItem("转换为微店原始链接", "showWeidianOriginalForm")
    .addItem("buy 链接互转（选区）", "showBuyLinkConvertForm")
    .addToUi();
}

var PRODUCT_LINK_API_DEFAULT_BASE_URL = "https://admin.river0413.top/admin-api"; // SparkTrack (T-CRM); 原为旧系统地址，正式环境请改为正式域名或设置脚本属性 TALENT_CRM_API_BASE_URL
var PRODUCT_LINK_JOB_RECOVERY_KEY = "productLinkJobRecovery";
var PRODUCT_LINK_SOURCE_CHANNEL_CODE = "WEIDIAN";
var PRODUCT_LINK_REPLACEMENT_BATCH_SIZE = 50;

// 常量定义（用于ID提取功能）
const MAX_REDIRECT_DEPTH = 5;
const MAX_DECODE_PASSES = 4;

// 一键替换链接接口
function replacementChannelLink(){
	var sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();
	  var selection = sheet.getSelection();
	  var range = selection.getActiveRange();
	  
	  if (!range) {
		SpreadsheetApp.getUi().alert('错误', '请先选择一些单元格才能使用此功能。', SpreadsheetApp.getUi().ButtonSet.OK);
		return;
	  }
	  
	  showInputForm(range);
}
// 🔤 将列字母转为数字（A → 1, M → 13）
function columnLetterToNumber(letter) {
  let num = 0;
  for (let i = 0; i < letter.length; i++) {
    num = num * 26 + (letter.charCodeAt(i) - 64);
  }
  return num;
}


function generateProductLinks() {
  const ui = SpreadsheetApp.getUi();
  const sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();
  const range = sheet.getActiveRange();
  const numRows = range.getNumRows();
  const numCols = range.getNumColumns();

  // 获取目标列
  const colInput = ui.prompt("请输入要写入链接的列（如 G 或 7）").getResponseText();
  if (!colInput) {
    ui.alert("❌ 操作取消");
    return;
  }

  let targetCol;
  if (/^[A-Za-z]+$/.test(colInput)) {
    targetCol = columnLetterToNumber(colInput.toUpperCase());
  } else if (/^\d+$/.test(colInput)) {
    targetCol = parseInt(colInput, 10);
  } else {
    ui.alert("❌ 无效的列输入");
    return;
  }

  // 获取替换模板
  const template = getTemplateFromUser(ui);
  if (!template) return;
 // 获取达人邀请码
  const inviteCode = getInviteCode(ui);
  if (!inviteCode) return;

 const urlTemplate = template.replace("{INVITE_CODE}", inviteCode);

  for (let i = 1; i <= numRows; i++) {
    for (let j = 1; j <= numCols; j++) {
      const cell = range.getCell(i, j);
      const value = cell.getValue().toString();
      const match = value.match(/\b\d{10}\b/);  // 提取 10 位数字

      if (match) {
        const productId = match[0];
        const finalUrl = urlTemplate.replace("{PRODUCT_ID}", productId);
        const linkCell = sheet.getRange(cell.getRow(), targetCol);

        const richText = SpreadsheetApp.newRichTextValue()
          .setText("LINK")
          .setLinkUrl(finalUrl)
          .build();

        linkCell.setRichTextValue(richText);
      }
    }
  }

  ui.alert("✅ 链接生成完成，已写入指定列！");
}

// 将列字母转换为数字（如 "G" → 7）
function columnLetterToNumber(letter) {
  let num = 0;
  for (let i = 0; i < letter.length; i++) {
    num = num * 26 + (letter.charCodeAt(i) - 64);
  }
  return num;
}





/**
 * 获取API数据（四参数版本）
 * @param {string} token - 复制令牌（字符串类型）
 * @param {string} businessId - 达人ID（仅保留参数位，不再使用：达人与目标店铺由令牌决定，原价复制，见 ADR-019）
 * @param {GoogleAppsScript.Spreadsheet.Range} inputData - 包含10位数字的单元格引用或文本（必传）
 * @param {number} - 可选的加价数据（仅保留参数位，不再使用）
 * @param {number} [current] - 当前单元格
 * @return 返回API结果
 * @customfunction
 */
function GET_PRODUCT_ID(token,businessId, inputData, markUp) {
  // SparkTrack：改调本系统复制接口；重新计算安全，已有对应关系直接返回已有商品ID。
  return fetchCopiedProductId_(token, inputData);
}



/**
 * 获取API数据（五参数版本）
 * @param {string} token - 复制令牌（字符串类型）
 * @param {string} appId - 店铺ID（仅保留参数位，不再使用：目标店铺由令牌或公司默认复制店铺决定，见 ADR-019）
 * @param {string} channelId - 渠道ID（仅保留参数位，不再使用）
 * @param {GoogleAppsScript.Spreadsheet.Range} inputData - 包含10位数字的单元格引用或文本（必传）
 * @param {number} - 可选的加价数据（仅保留参数位，不再使用，原价复制）
 * @param {number} [current] - 当前单元格
 * @return 返回API结果
 * @customfunction
 */
function GET_CHANNEL_PRODUCT_ID(token,appId,channelId, inputData, markUp) {
  // SparkTrack：改调本系统复制接口；重新计算安全，已有对应关系直接返回已有商品ID。
  return fetchCopiedProductId_(token, inputData);
}

// SparkTrack：两个公式函数共用。提交 1 条复制，再轮询任务结果；总时长控制在 25 秒内（Google 自定义函数上限 30 秒）。
function fetchCopiedProductId_(token, inputData) {
  try {
    var productId = extractTenDigitNumberFromUrl(inputData);
    var platform = "WEIDIAN";

    if (!productId) {
      productId = extractNumberFromUrl(inputData)
      platform = parseUrl(inputData);
    }

    if (!productId) {
      return "❌ 未能从链接中识别商品ID";
    }

    var item = { sourceChannelCode: platform || "WEIDIAN", sourceProductId: String(productId) };
    var job = productLinkApiFetch_("/external/product-links/convert", {
      method: "POST",
      contentType: "application/json",
      payload: JSON.stringify({ token: token, callerPlatform: "google_sheet_custom_function", items: [item] })
    });
    if (!job || !job.jobCode) {
      return "❌ 创建复制任务失败";
    }

    var deadline = Date.now() + 25000;
    while (true) {
      var data = productLinkApiFetch_("/external/product-links/jobs/" + encodeURIComponent(job.jobCode) + "/items/resolve", {
        method: "POST",
        contentType: "application/json",
        payload: JSON.stringify({ token: token, items: [item] })
      });
      var result = data && Array.isArray(data.items) ? data.items[0] : null;
      if (data && data.status === "ready" && result) {
        if (result.status === "success") return String(result.targetExternalProductId);
        if (result.status === "already_applied") return String(item.sourceProductId);
        if (result.status === "failed") return "❌ " + (result.errorMessage || "复制失败");
        if (result.status === "conflict") return "❌ " + (result.errorMessage || "商品在多个店铺之间冲突");
        return "❌ 任务中未找到该商品";
      }
      if (Date.now() >= deadline) {
        return "⏳ 复制处理中，请稍后重新计算";
      }
      Utilities.sleep(Math.min(1000, Math.max(0, deadline - Date.now())));
    }
  } catch (e) {
    return "❌ API请求失败: " + e.message;
  }
}


function replaceAffcodeInLinks() {
  const ui = SpreadsheetApp.getUi();
  const response = ui.prompt('请输入新的邀请码（affcode）');

  if (response.getSelectedButton() !== ui.Button.OK) return;

  const newAffcode = encodeURIComponent(response.getResponseText().trim());
  if (!newAffcode) {
    ui.alert('邀请码不能为空');
    return;
  }

  const sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();
  const range = sheet.getActiveRange();
  const richTexts = range.getRichTextValues();

  const updatedRichTexts = richTexts.map(row =>
    row.map(cell => {
      const text = cell.getText();
      const runs = cell.getRuns ? cell.getRuns() : null;

      if (runs && runs.length > 0) {
        const builder = SpreadsheetApp.newRichTextValue().setText(text);
        runs.forEach(run => {
          const start = run.getStartIndex();
          const end = run.getEndIndex();
          const link = run.getLinkUrl();

          if (link && link.includes('affcode=')) {
            const newUrl = link.replace(/affcode=[^&]+/, `affcode=${newAffcode}`);
            builder.setLinkUrl(start, end - 1, newUrl);
          } else if (link) {
            builder.setLinkUrl(start, end - 1, link); // 保留其他链接
          }
        });
        return builder.build();
      } else if (cell.getLinkUrl) {
        const url = cell.getLinkUrl();
        if (url && url.includes('affcode=')) {
          const newUrl = url.replace(/affcode=[^&]+/, `affcode=${newAffcode}`);
          return SpreadsheetApp.newRichTextValue()
            .setText(text)
            .setLinkUrl(newUrl)
            .build();
        }
      }

      return cell; // 无链接或无需更改
    })
  );

  range.setRichTextValues(updatedRichTexts);
  ui.alert('链接已成功替换邀请码！');
}



function extractTenDigitNumberFromUrl(url) {
  try {
    const decodedUrl = decodeURIComponent(url);
    const match = decodedUrl.match(/\b\d{10}\b/);
    return match ? match[0] : "";
  } catch (e) {
    return "URL 解码失败";
  }
}

function extractNumberFromUrl(url) {
  try {
    const decodedUrl = decodeURIComponent(url);

    // 先匹配 12 位
    let match = decodedUrl.match(/\b\d{12}\b/);
    if (match) {
      return match[0];
    }

    // 再匹配 13 位
    match = decodedUrl.match(/\b\d{13}\b/);
    return match ? match[0] : "";

  } catch (e) {
    return "URL 解码失败";
  }
}


// 从输入获取列号
function getColumnFromUser(ui, promptMessage) {
  const response = ui.prompt(promptMessage);
  if (response.getSelectedButton() !== ui.Button.OK) return null;

  const input = response.getResponseText().trim();
  const columnNumber = /^\d+$/.test(input) 
    ? parseInt(input) 
    : columnLetterToNumber(input.toUpperCase());

  return columnNumber > 0 ? columnNumber : ui.alert("❌ 无效列输入") && null;
}

// 获取模板并验证
function getTemplateFromUser(ui) {
  const response = ui.prompt(
    "请输入替换网站模板编号:\n"+
    "1. https://cnfans.com \n"+
    "2. https://www.kakobuy.com  \n"+
    "3. https://oopbuy.com   \n"+
    "4. https://www.acbuy.com  \n"+
    "5. https://hoobuy.com  \n"+
    "6. https://joyagoo.com  \n"+
    "7. https://mulebuy.com  \n"+
    "8. https://weidian.com  \n"+
    "9. https://www.allchinabuy \n"+
    "10. https://www.usfans.com  \n"+
    "11. https://www.itaobuy.com \n"+
    "12. https://hippoobuy.com \n"+
    "13. https://hipobuy.com  \n"+
    "14. https://www.fishgoo.com  \n"+
    "15. https://litbuy.com  \n"+
    "16. https://gtbuy.com  \n"+
    "17. https://niuniubox.com	\n"+
    "18. https://hubbuycn.com	\n"+
    "19. https://fansbuy.com/	\n"+
    "20. https://www.lovegobuy.com \n"+
    "21. https://www.superbuy.com \n"+
    "22. https://www.vigorbuy.com \n"+
    "23. https://www.bbdbuy.com \n"+
    "24. https://orientdig.com/ \n"+
    "25. https://cnshopper.com/ \n"+
    "26. https://www.sugargoo.com/ \n"+
    "27. https://www.cssbuy.com/ \n"+
    "28. https://www.bbdbuyeu.com/ \n"+
    "29. https://wheebuy.com/ \n"
     );

  if (response.getSelectedButton() !== ui.Button.OK) return null;

  const num = response.getResponseText().trim();
  const template = getModel(num,ui);

  return template;
}



// 获取模板并验证
function getInviteCode(ui) {
  const response = ui.prompt("请输入达人邀请码");

  if (response.getSelectedButton() !== ui.Button.OK) return null;

  const inviteCode = response.getResponseText().trim();

  return inviteCode;
}

// 提取商品ID（10位数字）
function extractTenDigitProductId(url) {
  try {
      // 1. 解码URL（处理%3D等编码字符）
      const decodedUrl = decodeURIComponent(url);

      // 2. 构造正则表达式匹配itemID后的10位数字
      // 模式说明：
      // - itemID= 匹配固定参数名
      // - (?<id>\d{10}) 命名捕获组，匹配连续10位数字
      const regex = /(?<id>\d{10})/;

      // 3. 执行匹配
      const match = decodedUrl.match(regex);

      // 4. 返回结果（未匹配到返回null）
      return match ? match.groups.id : "";
    } catch (error) {
      console.error("提取商品ID失败:", error);
      return "";
    }
}

// 列号转列名（如7→G）
function getColumnName(columnNumber) {
  let name = "";
  while (columnNumber > 0) {
    const remainder = (columnNumber - 1) % 26;
    name = String.fromCharCode(65 + remainder) + name;
    columnNumber = Math.floor((columnNumber - 1) / 26);
  }
  return name;
}

// 列名转列号（如G→7）
function columnLetterToNumber(letter) {
  let num = 0;
  for (let i = 0; i < letter.length; i++) {
    num = num * 26 + (letter.charCodeAt(i) - 64);
  }
  return num;
}


function syncGetRequest(shortUrl) {
  var apiUrl = productLinkApiBaseUrl_() + "/external/short-urls/resolve?url=" + encodeURIComponent(shortUrl);
  try {
    var response = UrlFetchApp.fetch(apiUrl, {
      method: "GET",
      contentType: "application/json",
      muteHttpExceptions: true
    });

    // 检查HTTP状态码
    if (response.getResponseCode() !== 200) {
      return "❌ API返回错误: " + response.getContentText();
    }

    var result = JSON.parse(response.getContentText());

    // SparkTrack 统一信封：code 为 0 时取 data.url（最终地址）
    if (Number(result.code) !== 0 || !result.data || !result.data.url) {
      return "❌ API未返回地址";
    }
    return result.data.url;

  } catch (e) {
    return "❌ API请求失败: " + e.message;
  }
}



// 辅助函数：从单元格提取 URL
function extractUrlFromCell(cell) {
  const formula = cell.getFormula();

  // 检查是否为 HYPERLINK 公式
  const match = formula.match(/=HYPERLINK\("([^"]+)"/i);
  if (match) {
    return match[1];
  }

  // 检查是否为富文本链接
  const richText = cell.getRichTextValue();
  if (richText) {
    const runs = richText.getRuns();
    for (let r = 0; r < runs.length; r++) {
      const link = runs[r].getLinkUrl();
      if (link) {
        return link;
      }
    }
  }

  // 检查单元格值是否为 URL
  const cellValue = cell.getValue();
  if (typeof cellValue === 'string' && cellValue.startsWith('http')) {
    return cellValue;
  }

  return null;
}

// 辅助函数：设置单元格值（添加错误处理）
function setCellValue(row, col, value) {
  try {
    SpreadsheetApp.getActiveSheet().getRange(row, col).setValue(value);
  } catch (e) {
    console.error(`设置单元格值失败 (${row}, ${col}): ${e}`);
  }
}

// 获取模板
                              function getModel(num) {

      if(num == 1){
          return "https://cnfans.com/product/?shop_type=weidian&id={PRODUCT_ID}&ref={INVITE_CODE}";
      }
      if(num == 2){
          return "https://www.kakobuy.com/item/details?url=https%3A%2F%2Fweidian.com%2Fitem.html%3FitemID%3D{PRODUCT_ID}&affcode={INVITE_CODE}";
      }
      if(num == 3){
          return "https://oopbuy.com/product/weidian/{PRODUCT_ID}?inviteCode={INVITE_CODE}";
      }
      if(num == 4){
          return "https://www.acbuy.com/product?id={PRODUCT_ID}&source=WD&u={INVITE_CODE}";
      }
      if(num == 5){
          return "https://hoobuy.com/product/2/{PRODUCT_ID}?inviteCode={INVITE_CODE}";
      }
      if(num == 6){
          return "https://joyagoo.com/product?platform=WEIDIAN&id={PRODUCT_ID}&ref={INVITE_CODE}";
      }
      if(num == 7){
          return "https://mulebuy.com/product/?shop_type=weidian&id={PRODUCT_ID}&ref={INVITE_CODE}";
      }
      if(num == 8){
          return "https://weidian.com/item.html?itemID={PRODUCT_ID}&spider_token={INVITE_CODE}";
      }
      if(num == 9){
          return   "https://www.allchinabuy.com/en/page/buy/?nTag=Home-search&from=search-input&_search=url&position=&url=https://weidian.com/item.html?itemID={PRODUCT_ID}&partnercode={INVITE_CODE}";
      }
      if(num == 10){
          return   "https://www.usfans.com/product/3/{PRODUCT_ID}?ref={INVITE_CODE}";
      }
      if(num == 11){
          return   "https://www.itaobuy.com/product-detail?url=https://weidian.com/item.html?itemID={PRODUCT_ID}&inviteCode={INVITE_CODE}";
      }
      if(num == 12){
          return   "https://hippoobuy.com/en_US/goods/detail?keyword=https://weidian.com/item.html?itemID={PRODUCT_ID}&invitor_id={INVITE_CODE}";
      }
      if(num == 13){
          return   "https://hipobuy.com/product/weidian/{PRODUCT_ID}?inviteCode={INVITE_CODE}";
      }
      if(num == 14){
          return   "https://www.fishgoo.com/#/product?productLink=https://weidian.com/item.html?itemID={PRODUCT_ID}&memberId={INVITE_CODE}";
      }
      if(num == 15){
          return   "https://litbuy.com/product/weidian/{PRODUCT_ID}?inviteCode={INVITE_CODE}";
      }
      if(num == 16){
          return   "https://gtbuy.com/product/weidian/{PRODUCT_ID}?inviteCode={INVITE_CODE}";
      }
      if(num == 17){
          return   "https://niuniubox.com/product-detail/?platform=micro&id={PRODUCT_ID}&inviteCode={INVITE_CODE}";
      }
      if(num == 18){
          return   "https://hubbuycn.com/product/item?url=https://weidian.com/item.html?itemID={PRODUCT_ID}&invitation_code={INVITE_CODE}";
      }
      if(num == 19){
          return   "https://fansbuy.com/item-micro-{PRODUCT_ID}.html?promotionCode={INVITE_CODE}";
      }
      if(num == 20){
          return   "https://www.lovegobuy.com/product?shop_type=weidian&id={PRODUCT_ID}&invite_code={INVITE_CODE}";
      }
      if(num == 21){
          return   "https://www.superbuy.com/en/page/buy/?nTag=Home-search&from=search-input&url=https%3A%2F%2Fweidian.com%2Fitem.html%3FitemID%3D{PRODUCT_ID}&partnercode={INVITE_CODE}";
      }
      if(num == 22){
          return   "https://vigorbuy.com/product/2/{PRODUCT_ID}?inviteCode={INVITE_CODE}";
      }
      if(num == 23){
          return   "https://bbdbuy.com/index/item/index.html?searchlang=en&url=https%3A%2F%2Fweidian.com%2Fitem.html%3FitemID%3D{PRODUCT_ID}&inviteCode={INVITE_CODE}";
      }
      if(num == 24){
          return   "https://orientdig.com/product/?shop_type=weidian&id={PRODUCT_ID}&ref={INVITE_CODE}";
      }
      if(num == 25){
          return   "https://cnshopper.com/goods/detail?keyword={PRODUCT_ID}&platform=weidian&invite_id={INVITE_CODE}";
      }
    if(num == 26){
         return   "https://www.sugargoo.com/products?productLink=https://weidian.com/item.html?itemID={PRODUCT_ID}&memberId={INVITE_CODE}";
    }
    if(num == 27){
         return   "https://www.cssbuy.com/item-micro-{PRODUCT_ID}.html?promotionCode={INVITE_CODE}";
    }
    if(num == 28){
        return   "https://www.bbdbuyeu.com/goods/WEIDIAN/{PRODUCT_ID}?inviteCode={INVITE_CODE}";
    }
    if(num == 29){
        return   "https://wheebuy.com/product/weidian/{PRODUCT_ID}?inviteCode={INVITE_CODE}";
    }
                                  
}

function parseUrl(url) {
  
    // 将URL转换为小写，以确保匹配不受大小写影响
    const lowerUrl = url.toLowerCase();
    
    // 检查URL中是否包含淘宝的域名
    if (lowerUrl.includes("taobao")) {
        return "TAOBAO";
    }
    // 检查URL中是否包含1688的域名
    else if (lowerUrl.includes("1688")) {
        return "ALI_1688";
    }
    // 都不符合则返回空字符串
    else {
        return "WEIDIAN";
    }
}


/**
 * 显示输入表单
 * @param {Range} range 用户选择的单元格范围
 */
function showInputForm(range) {
  var ui = SpreadsheetApp.getUi();
  var htmlOutput = HtmlService.createHtmlOutputFromFile('submitFrom')
    .setWidth(800)
    .setHeight(550);
  
  // 将范围信息传递给HTML
  htmlOutput.setTitle('填写单元格信息 - 选择范围: ' + range.getA1Notation());
  
  ui.showModalDialog(htmlOutput, '填写单元格信息');
}

/**
 * 处理表单提交
 * @param {Object} formData 表单数据
 */
function processFormSubmission(formData) {
  formData = Object.assign({}, formData || {}, { replacementModifiedCells: 0 });
  var spreadsheet = SpreadsheetApp.getActiveSpreadsheet();
  var range = productLinkReplacementRange_(formData, spreadsheet);
  var collected = collectProductLinkReplacementRange_(range, formData);
  saveProductLinkFormDraft_(formData);

  if (!collected.targets.length) {
    return { success: true, pending: false, message: "没有找到可创建的商品ID。" };
  }

  spreadsheet.toast("已找到 " + collected.targets.length + " 个商品，正在创建任务", "提示", 5);
  var job = createProductLinkConvertJob_(formData.token, collected.targets);
  if (!job || !job.jobCode) throw new Error("创建商品任务失败：接口未返回任务编号");
  formData.productJobCode = job.jobCode;
  persistProductLinkJobRecovery_(job.jobCode, formData);
  return finishProductLinkReplacement_(job.jobCode, formData);
}

function resumeProductLinkReplacement(formData) {
  var context = buildProductLinkReplacementContext_(formData, true);
  return finishProductLinkReplacement_(context.formData.productJobCode, context.formData);
}

function checkProductLinkReplacementOnce(formData) {
  var context = buildProductLinkReplacementContext_(formData);
  return finishProductLinkReplacement_(context.formData.productJobCode, context.formData);
}

// 固定本轮工作表和范围；商品结果始终按来源ID匹配，与选区位置无关。
function productLinkReplacementRange_(formData, spreadsheet) {
  if (!formData.rangeA1Notation || String(formData.rangeA1Notation).indexOf("!") >= 0) {
    throw new Error("请先选择要处理的单元格范围");
  }
  if (formData.spreadsheetId && String(formData.spreadsheetId) !== String(spreadsheet.getId())) {
    throw new Error("本轮任务属于另一个表格，请重新选择范围后继续");
  }
  var sheet = formData.sheetId !== undefined && formData.sheetId !== null && formData.sheetId !== ""
    ? spreadsheet.getSheetById(Number(formData.sheetId)) : spreadsheet.getActiveSheet();
  if (!sheet) throw new Error("本轮工作表已不存在，请重新选择范围后继续");
  if ((formData.replacementSheetRows && Number(formData.replacementSheetRows) !== sheet.getMaxRows()) ||
      (formData.replacementSheetColumns && Number(formData.replacementSheetColumns) !== sheet.getMaxColumns())) {
    throw new Error("工作表行列数量已变化，请重新选择范围后继续");
  }
  var range = sheet.getRange(formData.rangeA1Notation);
  // 整列选择只读取已使用的行列，避免处理大量空白格。
  var rows = formData.replacementScope ? range.getNumRows() : Math.min(range.getNumRows(), sheet.getLastRow() - range.getRow() + 1);
  var columns = formData.replacementScope ? range.getNumColumns() : Math.min(range.getNumColumns(), sheet.getLastColumn() - range.getColumn() + 1);
  if (rows <= 0 || columns <= 0) throw new Error("选区内没有可处理的内容");
  if (rows * columns > 20000) throw new Error("选区过大，请每次选择不超过 20000 个已用区域内的单元格");
  range = sheet.getRange(range.getRow(), range.getColumn(), rows, columns);
  formData.spreadsheetId = spreadsheet.getId();
  formData.sheetId = sheet.getSheetId();
  formData.sheetName = sheet.getName();
  formData.rangeA1Notation = range.getA1Notation();
  formData.replacementSheetRows = sheet.getMaxRows();
  formData.replacementSheetColumns = sheet.getMaxColumns();
  return range;
}

function collectProductLinkReplacementRange_(range, formData) {
  return collectProductLinkReplacementTargets_(range, range.getValues(),
    range.getRichTextValues(), range.getFormulas(), formData);
}

function productLinkReplacementScope_(formData) {
  var identity = [formData.productJobCode, formData.spreadsheetId, String(formData.sheetId),
    formData.rangeA1Notation, formData.replacementSheetRows, formData.replacementSheetColumns,
    formData.platform, formData.templateId, formData.templateCode, formData.invitationCode,
    formData.cellContent, formData.newContent, formData.useFormula === true, formData.token];
  return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, JSON.stringify(identity),
    Utilities.Charset.UTF_8).map(function(value) { return ("0" + (value & 255).toString(16)).slice(-2); }).join("");
}

function productLinkProgressStore_() {
  return PropertiesService.getDocumentProperties() || PropertiesService.getScriptProperties();
}

function productLinkReadProgress_(scope) {
  var value = productLinkProgressStore_().getProperty("productLinkProgress:" + scope);
  if (!value) return null;
  try { return JSON.parse(value); } catch (error) { throw new Error("已保存的替换进度无效，请重新选择范围"); }
}

function productLinkProgressFields_() {
  return ["replacementCursor", "replacementModifiedCells", "replacementAlreadyAppliedCells",
    "replacementUnmatchedCells", "replacementFailedCount"];
}

// prepare 和 apply 都在文档锁内提交；按 scope 保存，不允许较早的请求覆盖较新的游标。
function productLinkCommitProgress_(formData, lockHeld) {
  var lock = null;
  if (!lockHeld) {
    lock = LockService.getDocumentLock() || LockService.getScriptLock();
    if (!lock.tryLock(1000)) throw new Error("另一个链接转换正在保存进度，请稍后继续替换");
  }
  try {
    var store = productLinkProgressStore_(), key = "productLinkProgress:" + formData.replacementScope;
    var previous = productLinkReadProgress_(formData.replacementScope), saved = {};
    if (previous && Number(previous.replacementCursor) >= Number(formData.replacementCursor)) {
      productLinkProgressFields_().forEach(function(field) { formData[field] = Number(previous[field] || 0); });
    }
    productLinkProgressFields_().forEach(function(field) { saved[field] = Number(formData[field] || 0); });
    saved.updatedAt = Date.now();
    if (!previous) {
      // Properties 只保存轻量进度，保留最近 100 个范围，避免长期积累占满配额。
      var all = store.getProperties();
      var keys = Object.keys(all).filter(function(name) { return name.indexOf("productLinkProgress:") === 0; });
      keys.sort(function(left, right) {
        try { return Number(JSON.parse(all[left]).updatedAt || 0) - Number(JSON.parse(all[right]).updatedAt || 0); }
        catch (error) { return 0; }
      });
      while (keys.length >= 100) store.deleteProperty(keys.shift());
    }
    store.setProperty(key, JSON.stringify(saved));
    persistProductLinkJobRecovery_(formData.productJobCode, formData);
  } finally { if (lock) lock.releaseLock(); }
}

function buildProductLinkReplacementContext_(supplied) {
  supplied = supplied || {};
  var formData = mergeProductLinkRecoveryFormData_(supplied);
  // 刷新或重新选择时没有 scope：绑定当前尺寸；自动继续带 scope，仍严格拒绝结构变化。
  if (supplied.rangeA1Notation && !supplied.replacementScope) {
    delete formData.replacementSheetRows;
    delete formData.replacementSheetColumns;
    delete formData.replacementScope;
  }
  // 旧页面没有工作表 ID 的新选区按当前工作表处理。
  if (supplied.rangeA1Notation && supplied.sheetId === undefined) {
    delete formData.sheetId;
    delete formData.spreadsheetId;
    delete formData.replacementSheetRows;
    delete formData.replacementSheetColumns;
    delete formData.replacementScope;
  }
  if (!formData.productJobCode) throw new Error("请填写商品创建任务编号");
  var spreadsheet = SpreadsheetApp.getActiveSpreadsheet();
  var range = productLinkReplacementRange_(formData, spreadsheet);
  var scope = productLinkReplacementScope_(formData);
  var recovery = getDraft(PRODUCT_LINK_JOB_RECOVERY_KEY);
  var legacySaved = recovery && recovery.formData;
  var saved = productLinkReadProgress_(scope) || (legacySaved && legacySaved.replacementScope === scope ? legacySaved : null);
  var fields = productLinkProgressFields_();
  if (saved) {
    // 服务端已确认进度优先，RPC 回包丢失时也不会回退或重复计数。
    fields.forEach(function(key) { formData[key] = Number(saved[key] || 0); });
  } else if (formData.replacementScope !== scope) {
    fields.forEach(function(key) { formData[key] = 0; });
  }
  formData.replacementScope = scope;
  var total = range.getNumRows() * range.getNumColumns();
  if (!Number.isInteger(formData.replacementCursor) || formData.replacementCursor < 0 || formData.replacementCursor > total) {
    throw new Error("替换进度无效，请重新选择范围后继续");
  }
  return { formData: formData, spreadsheet: spreadsheet, range: range };
}

// 只向前读取当前批次，最多扫描 500 个单元格；不为每一批重读整个选区。
function collectProductLinkReplacementBatch_(context) {
  var formData = context.formData, range = context.range, sheet = range.getSheet();
  var columns = range.getNumColumns(), total = range.getNumRows() * columns;
  var cursor = Number(formData.replacementCursor || 0), from = cursor, cells = [], requested = [], seen = Object.create(null);
  var templates = productLinkTemplatesFromFormData_(formData);
  formData.linkTemplates = templates;
  while (cursor < total && cells.length < PRODUCT_LINK_REPLACEMENT_BATCH_SIZE && cursor - from < 500) {
    var row = range.getRow() + Math.floor(cursor / columns), column = range.getColumn() + cursor % columns;
    var budget = Math.min(50, 500 - (cursor - from));
    var width = Math.min(columns - cursor % columns, budget), height = 1;
    if (cursor % columns === 0 && columns <= budget) {
      height = Math.min(Math.floor(budget / columns), Math.floor((total - cursor) / columns));
      width = columns;
    }
    var slice = sheet.getRange(row, column, height, width), length = height * width;
    var values = slice.getValues(), richValues = slice.getRichTextValues(), formulas = slice.getFormulas();
    for (var i = 0; i < length && cells.length < PRODUCT_LINK_REPLACEMENT_BATCH_SIZE; i++, cursor++) {
      var r = Math.floor(i / width), c = i % width;
      if (normalizeMatchValue_(values[r][c]) !== normalizeMatchValue_(formData.cellContent)) continue;
      var link = resolveOriginalLink_(values[r][c], richValues[r][c], formulas[r][c]);
      var source = resolveSourceProductInfo_(link, templates);
      if (!source.productId) continue;
      var channel = String(source.platform || PRODUCT_LINK_SOURCE_CHANNEL_CODE).toUpperCase();
      var id = String(source.productId), key = productLinkProductKey_(channel, id);
      if (!/^[A-Z][A-Z0-9_]*:\d+$/.test(key)) throw new Error("来源商品 ID 或平台无效，已停止查询");
      cells.push({ position: cursor, row: row + r, column: column + c,
        originalContent: String(values[r][c]), originalLink: link, originalFormula: String(formulas[r][c] || ""),
        sourceChannelCode: channel, sourceProductId: id });
      if (!seen[key]) { seen[key] = true; requested.push({ sourceChannelCode: channel, sourceProductId: id }); }
    }
  }
  return { fromCursor: from, toCursor: cursor, totalCells: total, cells: cells, requested: requested };
}

function resolveProductLinkBatch_(jobCode, formData, requested) {
  var data = productLinkApiFetch_("/external/product-links/jobs/" + encodeURIComponent(jobCode) + "/items/resolve", {
    method: "POST", contentType: "application/json",
    payload: JSON.stringify({ token: formData.token, items: requested })
  });
  if (!data || data.jobCode !== String(jobCode) || ["ready", "preparing", "job_pending"].indexOf(data.status) < 0) {
    throw new Error("任务批次结果格式错误，已停止写入");
  }
  if (data.status !== "ready") return data;
  if (typeof data.resultVersion !== "string" || !data.resultVersion || !Array.isArray(data.items) || data.items.length !== requested.length) {
    throw new Error("任务批次结果不完整，已停止写入");
  }
  var expected = Object.create(null), seen = Object.create(null);
  requested.forEach(function(item) { expected[productLinkProductKey_(item.sourceChannelCode, item.sourceProductId)] = true; });
  var index = { bySource: Object.create(null), targetIds: Object.create(null), unsuccessful: Object.create(null) };
  data.items.forEach(function(item) {
    if (!item || typeof item.sourceProductId !== "string" || !/^\d+$/.test(item.sourceProductId) ||
        typeof item.sourceChannelCode !== "string" || !/^[A-Z][A-Z0-9_]*$/.test(item.sourceChannelCode)) {
      throw new Error("任务批次来源商品格式错误，已停止写入");
    }
    var key = productLinkProductKey_(item.sourceChannelCode, item.sourceProductId);
    if (!expected[key] || seen[key] || ["success", "failed", "not_found", "conflict", "already_applied"].indexOf(item.status) < 0) {
      throw new Error("任务批次来源商品不匹配，已停止写入");
    }
    seen[key] = true;
    if (item.status === "success") {
      if (typeof item.targetExternalProductId !== "string" || !/^\d+$/.test(item.targetExternalProductId) ||
          typeof item.targetChannelCode !== "string" || !/^[A-Z][A-Z0-9_]*$/.test(item.targetChannelCode) ||
          typeof item.targetStoreId !== "string") throw new Error("任务批次目标商品格式错误，已停止写入");
      index.bySource[key] = { productId: item.targetExternalProductId, channel: item.targetChannelCode, storeId: item.targetStoreId };
      index.targetIds[productLinkProductKey_(item.targetChannelCode, item.targetExternalProductId)] = true;
    } else if (item.status === "already_applied") index.targetIds[key] = true;
    else if (item.status === "failed" || item.status === "conflict") index.unsuccessful[key] = true;
  });
  data.results = index;
  return data;
}

function productLinkBatchProgressResponse_(formData, total, phase, message, extra, lockHeld) {
  productLinkCommitProgress_(formData, lockHeld);
  var pending = phase === "next_batch" || phase === "applying" || phase === "waiting";
  var details = { formData: Object.assign(sanitizeProductLinkFormData_(formData), { linkTemplates: formData.linkTemplates }), progress: {
    modifiedCells: Number(formData.replacementModifiedCells || 0),
    alreadyAppliedCells: Number(formData.replacementAlreadyAppliedCells || 0),
    unmatchedCells: Number(formData.replacementUnmatchedCells || 0), failedCount: Number(formData.replacementFailedCount || 0),
    processedCells: Number(formData.replacementCursor || 0), totalCells: total } };
  Object.assign(details, extra || {});
  return { success: phase === "complete" && !details.progress.failedCount, pending: pending, phase: phase,
    jobCode: formData.productJobCode, message: message, details: details };
}

function finishProductLinkReplacement_(jobCode, supplied) {
  var context = buildProductLinkReplacementContext_(Object.assign({}, supplied, { productJobCode: jobCode }));
  var formData = context.formData;
  var batch = collectProductLinkReplacementBatch_(context);
  if (!batch.cells.length) {
    formData.replacementCursor = batch.toCursor;
    return productLinkBatchProgressResponse_(formData, batch.totalCells,
      batch.toCursor < batch.totalCells ? "next_batch" : "complete", "已处理当前选区，替换 " + Number(formData.replacementModifiedCells || 0) + " 个单元格。");
  }
  var resolved = resolveProductLinkBatch_(jobCode, formData, batch.requested);
  if (resolved.status !== "ready") {
    return productLinkBatchProgressResponse_(formData, batch.totalCells,
      resolved.status === "preparing" ? "cache_preparing" : "waiting",
      resolved.status === "preparing" ? "任务结果缓存正在准备中，进度已保留，请稍后点击继续替换。" : "任务还在处理中，稍后会自动检查。",
      { replacementSnapshot: null });
  }
  var snapshot = { version: 2, jobCode: String(jobCode), scope: formData.replacementScope,
    job: { jobCode: String(jobCode), status: "completed" }, resultVersion: resolved.resultVersion,
    fromCursor: batch.fromCursor, toCursor: batch.toCursor, totalCells: batch.totalCells,
    cells: batch.cells, results: resolved.results, linkTemplates: formData.linkTemplates };
  return productLinkBatchProgressResponse_(formData, batch.totalCells, "applying", "本批结果已获取，正在替换。",
    { job: snapshot.job, replacementSnapshot: snapshot });
}

function applyProductLinkReplacementBatch(supplied, snapshot, startNewRun) {
  var context = buildProductLinkReplacementContext_(supplied), formData = context.formData;
  var index = requireProductLinkReplacementResults_(formData.productJobCode, snapshot);
  var range = context.range, total = range.getNumRows() * range.getNumColumns();
  if (snapshot.scope !== formData.replacementScope || snapshot.totalCells !== total ||
      !Number.isInteger(snapshot.fromCursor) || !Number.isInteger(snapshot.toCursor) ||
      snapshot.fromCursor < 0 || snapshot.toCursor > total || snapshot.toCursor <= snapshot.fromCursor ||
      !Array.isArray(snapshot.cells) || !snapshot.cells.length || snapshot.cells.length > 50 ||
      formData.replacementCursor < snapshot.fromCursor) throw new Error("本批结果与当前任务或选区不一致，已停止写入");
  var last = snapshot.fromCursor - 1;
  snapshot.cells.forEach(function(item) {
    if (!item || !Number.isInteger(item.position) || item.position <= last || item.position >= snapshot.toCursor ||
        item.row !== range.getRow() + Math.floor(item.position / range.getNumColumns()) ||
        item.column !== range.getColumn() + item.position % range.getNumColumns() ||
        typeof item.originalContent !== "string" || typeof item.originalFormula !== "string" ||
        typeof item.originalLink !== "string" || typeof item.sourceProductId !== "string" ||
        !/^[A-Z][A-Z0-9_]*:\d+$/.test(productLinkProductKey_(item.sourceChannelCode, item.sourceProductId))) {
      throw new Error("本批单元格数据无效，已停止写入");
    }
    last = item.position;
  });
  formData.linkTemplates = Array.isArray(formData.linkTemplates) && formData.linkTemplates.length ? formData.linkTemplates : snapshot.linkTemplates;
  if (!Array.isArray(formData.linkTemplates) || !formData.linkTemplates.length) throw new Error("链接规则已失效，请刷新配置后继续替换");
  var selected = selectedProductLinkTemplateFromFormData_(formData);
  var outputChannel = String(selected && selected.channelCode || "WEIDIAN").toUpperCase();
  var lock = LockService.getDocumentLock() || LockService.getScriptLock();
  if (!lock.tryLock(1000)) throw new Error("另一个链接转换正在写入，请稍后继续替换");
  var failedItems = [], paused = null, deadline = Date.now() + 180000;
  try {
    // 在锁内再次读取持久化游标，以处理另一执行刚完成的同批次。
    var latest = buildProductLinkReplacementContext_(formData).formData;
    ["replacementCursor", "replacementModifiedCells", "replacementAlreadyAppliedCells", "replacementUnmatchedCells", "replacementFailedCount"].forEach(function(key) { formData[key] = latest[key]; });
    for (var i = 0; i < snapshot.cells.length; i++) {
      var item = snapshot.cells[i];
      if (item.position < formData.replacementCursor) continue;
      if (Date.now() >= deadline) { paused = new Error("本批写入时间较长，请点击继续替换以复用当前结果"); break; }
      try {
        var cell = range.getSheet().getRange(item.row, item.column);
        var content = cell.getValue(), rich = cell.getRichTextValue(), formula = cell.getFormula();
        var link = resolveOriginalLink_(content, rich, formula);
        var source = resolveSourceProductInfo_(link, formData.linkTemplates);
        var key = productLinkProductKey_(item.sourceChannelCode, item.sourceProductId);
        var currentKey = productLinkProductKey_(source.platform, source.productId), matched = index.bySource[key];
        if (index.targetIds[currentKey]) {
          // 上次写入/flush 后回包或保存失败时，当前批可从实际目标链接确认已完成写入。
          if (matched && currentKey === productLinkProductKey_(matched.channel, matched.productId) &&
              link === buildProductLink_(formData.platform, formData.invitationCode, matched.productId) &&
              String(content) === String(formData.newContent === null || formData.newContent === undefined ? "" : formData.newContent)) {
            formData.replacementModifiedCells++;
          } else formData.replacementAlreadyAppliedCells++;
        }
        else if (currentKey !== key || String(content) !== item.originalContent || link !== item.originalLink || String(formula || "") !== item.originalFormula) {
          formData.replacementFailedCount++; failedItems.push(cell.getA1Notation() + "：单元格内容已变化，保留当前内容");
        } else if (index.unsuccessful[key] || (matched && matched.channel !== outputChannel)) {
          formData.replacementFailedCount++; failedItems.push(cell.getA1Notation() + "：任务失败或结果冲突，保留原链接");
        } else if (!matched) formData.replacementUnmatchedCells++;
        else {
          var newLink = buildProductLink_(formData.platform, formData.invitationCode, matched.productId);
          updateProductLinkCell_(cell, rich, formData.newContent, newLink, formData.useFormula === true);
          formData.replacementModifiedCells++;
        }
        formData.replacementCursor = item.position + 1;
      } catch (error) { paused = error; break; }
    }
    SpreadsheetApp.flush();
    if (!paused) formData.replacementCursor = Math.max(formData.replacementCursor, snapshot.toCursor);
    var phase = paused ? "apply_paused" : formData.replacementCursor < total ? "next_batch" : "complete";
    return productLinkBatchProgressResponse_(formData, total, phase,
      paused ? "当前批次写入暂停，进度和结果已保留：" + paused.message : "已按商品 ID 替换 " + formData.replacementModifiedCells +
        " 个单元格；未匹配 " + formData.replacementUnmatchedCells + " 个，失败或冲突 " + formData.replacementFailedCount + " 个。",
      { failedItems: failedItems.slice(0, 10), replacementSnapshot: paused ? snapshot : null }, true);
  } finally { lock.releaseLock(); }
}

function requireProductLinkReplacementResults_(jobCode, snapshot) {
  if (!snapshot || snapshot.version !== 2 || snapshot.jobCode !== String(jobCode) ||
      !snapshot.job || snapshot.job.jobCode !== String(jobCode) ||
      !isProductLinkJobFinished_(snapshot.job.status) ||
      typeof snapshot.resultVersion !== "string" || !snapshot.resultVersion) {
    throw new Error("任务结果已失效，请重新打开弹窗并使用任务编号继续替换");
  }
  var results = snapshot.results;
  function isMap(value) { return value && typeof value === "object" && !Array.isArray(value); }
  if (!results || !isMap(results.bySource) || !isMap(results.targetIds) || !isMap(results.unsuccessful)) {
    throw new Error("任务结果格式错误，已停止写入");
  }
  function validKey(key) { return /^[A-Z][A-Z0-9_]*:\d+$/.test(key); }
  var index = { bySource: Object.create(null), targetIds: Object.create(null), unsuccessful: Object.create(null) };
  Object.keys(results.targetIds).forEach(function(key) {
    if (!validKey(key) || results.targetIds[key] !== true) throw new Error("任务目标商品数据错误，已停止写入");
    index.targetIds[key] = true;
  });
  Object.keys(results.unsuccessful).forEach(function(key) {
    if (!validKey(key) || results.unsuccessful[key] !== true) throw new Error("任务失败商品数据错误，已停止写入");
    index.unsuccessful[key] = true;
  });
  Object.keys(results.bySource).forEach(function(key) {
    var item = results.bySource[key];
    if (!validKey(key) || !item || typeof item.productId !== "string" || !/^\d+$/.test(item.productId) ||
        typeof item.channel !== "string" || !/^[A-Z][A-Z0-9_]*$/.test(item.channel) ||
        typeof item.storeId !== "string" || (item.conflict !== undefined && typeof item.conflict !== "boolean") ||
        !index.targetIds[item.channel + ":" + item.productId]) {
      throw new Error("任务来源或目标商品数据错误，已停止写入");
    }
    index.bySource[key] = { productId: item.productId, channel: item.channel, storeId: item.storeId,
      conflict: item.conflict === true };
  });
  return index;
}

function collectProductLinkReplacementTargets_(range, values, richTextValues, formulas, formData) {
  var targets = [];
  var results = [];
  var templates = productLinkTemplatesFromFormData_(formData);
  var selectedTemplate = selectedProductLinkTemplateFromFormData_(Object.assign({}, formData, { linkTemplates: templates }));

  for (var i = 0; i < values.length; i++) {
    for (var j = 0; j < values[i].length; j++) {
      try {
        var cell = range.getCell(i + 1, j + 1);
        var originalContent = values[i][j];
        var richText = richTextValues[i][j];
        var formula = formulas[i][j];
        var originalLink = resolveOriginalLink_(originalContent, richText, formula);

        results.push({
          cell: cell.getA1Notation(),
          originalContent: originalContent,
          originalLink: originalLink
        });

        if (normalizeMatchValue_(originalContent) !== normalizeMatchValue_(formData.cellContent)) {
          continue;
        }

        var productInfo = resolveSourceProductInfo_(
          originalLink,
          templates
        );

        if (!productInfo.productId) {
          continue;
        }

        targets.push({
          cell: cell,
          richText: richText,
          originalContent: originalContent,
          originalLink: originalLink,
          originalFormula: formula,
          sourceProductId: productInfo.productId,
          sourceChannelCode: productInfo.platform || (selectedTemplate ? selectedTemplate.channelCode : null) || "WEIDIAN",
          ruleId: productInfo.ruleId || (selectedTemplate ? selectedTemplate.id : null)
        });
      } catch (e) {
        console.log("收集商品ID失败：" + e.message);
      }
    }
  }

  return {
    targets: targets,
    results: results
  };
}

function resolveOriginalLink_(originalContent, richText, formula) {
  if (richText && richText.getLinkUrl()) {
    console.log("直接获取到了单元格跳转链接")
    return richText.getLinkUrl();
  }

  if (formula && formula.startsWith("=HYPERLINK(")) {
    console.log("HYPERLINK公式解析出了跳转链接")
    var match = formula.match(/=HYPERLINK\("([^"]+)"/i);
    if (match && match[1]) {
      return match[1];
    }
  }

  if (typeof originalContent === "string" && originalContent.match(/https?:\/\/.+/)) {
    console.log("正则匹配链接：" + originalContent)
    return originalContent;
  }

  return null;
}

function resolveSourceProductInfo_(originalLink) {
  var templates = arguments.length > 1 ? arguments[1] : [];
  var matched = matchProductLinkTemplate_(originalLink, templates);
  if (matched && matched.productId) {
    return {
      productId: matched.productId,
      platform: matched.channelCode || PRODUCT_LINK_SOURCE_CHANNEL_CODE,
      ruleId: matched.templateId,
      templateCode: matched.templateCode,
      invitationCode: matched.invitationCode
    };
  }

  var realUrl = originalLink;
  var platform = parseUrl(originalLink || "");
  var productId = normalizeProductId_(extractTenDigitNumberFromUrl(originalLink || ""));

  if (!productId) {
    productId = normalizeProductId_(extractNumberFromUrl(realUrl || ""));
    console.log("解析12/13位产品ID结果：" + productId)
  }

  if (!productId && originalLink) {
    console.log("开始进行获取重定向url...")
    realUrl = syncGetRequest(originalLink);
    console.log("获取重定向url结果:" + realUrl)
    if (realUrl) {
      platform = parseUrl(realUrl);
      productId = normalizeProductId_(extractTenDigitNumberFromUrl(realUrl));
      if (!productId) {
        productId = normalizeProductId_(extractNumberFromUrl(realUrl));
      }
    }
  }

  return {
    productId: productId,
    platform: platform
  };
}

function createProductLinkConvertJob_(token, targets) {
  var items = targets.map(function(target) {
    return {
      sourceChannelCode: target.sourceChannelCode,
      sourceProductId: target.sourceProductId,
      ruleId: target.ruleId
    };
  });

  return productLinkApiFetch_("/external/product-links/convert", {
    method: "POST",
    contentType: "application/json",
    payload: JSON.stringify({
      token: token,
      callerPlatform: "google_sheet_plugin",
      items: items
    })
  });
}

function checkProductLinkJobFinishedOnce_(jobCode) {
  var job = fetchProductLinkJob_(jobCode);
  return {
    finished: isProductLinkJobFinished_(job.status),
    job: job
  };
}

function fetchProductLinkJob_(jobCode) {
  return productLinkApiFetch_("/external/product-links/jobs/" + encodeURIComponent(jobCode), {
    method: "GET"
  });
}

function isProductLinkJobFinished_(status) {
  var normalized = String(status || "").toLowerCase();
  return normalized === "completed" || normalized === "failed" || normalized === "partial_failed";
}

function isProductLinkJobFinishedByCounts_(job) {
  if (!job) return false;
  var totalCount = Number(job.totalCount || 0);
  if (totalCount <= 0) return false;
  var resolvedCount = Number(job.successCount || 0) + Number(job.failCount || 0);
  return resolvedCount >= totalCount;
}

function fetchProductLinkJobItems_(jobCode) {
  var items = productLinkApiFetch_("/external/product-links/jobs/" + encodeURIComponent(jobCode) + "/items", {
    method: "GET"
  }, true);
  return items;
}

function productLinkProductKey_(channel, productId) {
  var code = String(channel || "").trim().toUpperCase();
  var id = normalizeProductId_(productId);
  return code && id ? code + ":" + id : "";
}

function productLinkSuccessfulResults_(jobItems) {
  if (!Array.isArray(jobItems)) throw new Error("任务明细格式错误，已停止写入");
  var bySource = Object.create(null);
  var targetIds = Object.create(null);
  var unsuccessful = Object.create(null);
  jobItems.forEach(function(item) {
    if (!item) return;
    var key = productLinkProductKey_(item.sourceChannelCode, item.sourceProductId);
    if (String(item.status || "").toLowerCase() !== "success") {
      if (key) unsuccessful[key] = true;
      return;
    }
    var targetChannel = String(item.targetChannelCode || item.sourceChannelCode || "").trim().toUpperCase();
    var targetId = normalizeProductId_(item.targetExternalProductId);
    if (!key || !targetChannel || !targetId) throw new Error("成功任务明细缺少来源或目标商品信息，已停止写入");
    targetIds[productLinkProductKey_(targetChannel, targetId)] = true;
    var candidate = { productId: targetId, channel: targetChannel, storeId: String(item.targetStoreId || "") };
    var existing = bySource[key];
    if (!existing) bySource[key] = candidate;
    else if (existing.channel !== candidate.channel || existing.storeId !== candidate.storeId) {
      existing.conflict = true;
    } else if (candidate.productId < existing.productId) {
      // 同一个来源可能复制成功多次，固定选择一个结果；不依赖明细排序或inputIndex。
      existing.productId = candidate.productId;
    }
  });
  return { bySource: bySource, targetIds: targetIds, unsuccessful: unsuccessful };
}

function applyProductLinkJobItems_(targets, jobItems, formData, spreadsheet) {
  return applyProductLinkJobResults_(targets, productLinkSuccessfulResults_(jobItems), formData, spreadsheet);
}

function applyProductLinkJobResults_(targets, index, formData, spreadsheet) {
  var templates = productLinkTemplatesFromFormData_(formData);
  var selectedTemplate = selectedProductLinkTemplateFromFormData_(Object.assign({}, formData, { linkTemplates: templates }));
  var outputChannel = String(selectedTemplate && selectedTemplate.channelCode || "WEIDIAN").toUpperCase();
  var result = { modifiedCells: 0, alreadyAppliedCells: 0, unmatchedCells: 0,
    failedItems: [], failedCount: 0, pending: false };
  var deadline = Date.now() + 180000;
  function fail(target, reason) {
    result.failedCount++;
    if (result.failedItems.length < 10) {
      result.failedItems.push(target.cell.getA1Notation() + "：" + String(reason).slice(0, 240));
    }
  }
  targets.forEach(function(target) {
    // 此字段来自本轮刚读取的实际链接，不是任务序号。
    var key = productLinkProductKey_(target.sourceChannelCode, target.sourceProductId);
    if (index.targetIds[key]) { result.alreadyAppliedCells++; return; }
    var matched = index.bySource[key];
    if (!matched) {
      if (index.unsuccessful[key]) fail(target, "此来源商品未复制成功，保留原链接");
      else result.unmatchedCells++;
      return;
    }
    if (matched.conflict || matched.channel !== outputChannel) {
      fail(target, "成功结果存在目标店铺或平台冲突，保留原链接");
      return;
    }
    if (result.modifiedCells >= PRODUCT_LINK_REPLACEMENT_BATCH_SIZE || Date.now() >= deadline) {
      result.pending = true;
      return;
    }
    try {
      // 写入前再次检查实际格子，避免读表后用户编辑了商品。
      var content = target.cell.getValue();
      var richText = target.cell.getRichTextValue();
      var formula = target.cell.getFormula();
      var link = resolveOriginalLink_(content, richText, formula);
      var current = resolveSourceProductInfo_(link, templates);
      var currentKey = productLinkProductKey_(current.platform, current.productId);
      if (index.targetIds[currentKey]) { result.alreadyAppliedCells++; return; }
      if (currentKey !== key || String(content) !== String(target.originalContent) ||
          link !== target.originalLink || String(formula || "") !== String(target.originalFormula || "")) {
        fail(target, "单元格内容已变化，保留当前内容");
        return;
      }
      // 只使用当前来源ID对应的成功结果，失败明细和未匹配ID均不会进入写入。
      var newLink = buildProductLink_(formData.platform, formData.invitationCode, matched.productId);
      updateProductLinkCell_(target.cell, richText, formData.newContent, newLink, formData.useFormula === true);
      result.modifiedCells++;
    } catch (error) {
      fail(target, error.message);
    }
  });
  SpreadsheetApp.flush();
  return result;
}

function buildProductLink_(template, invitationCode, productId) {
  if (!template || template.indexOf("{PRODUCT_ID}") < 0) {
    throw new Error("替换链接模板不能为空，并且必须包含 {PRODUCT_ID}");
  }
  return template.replace("{INVITE_CODE}", invitationCode || "").replace("{PRODUCT_ID}", productId);
}

function updateProductLinkCell_(cell, richText, newContent, newLink, useFormula) {
  var text = newContent === null || newContent === undefined ? "" : String(newContent);
  cell.setDataValidation(null);

  if (!newLink) {
    cell.setValue(text);
    return;
  }

  if (richText && richText.getText()) {
    var newRichText = richText.copy()
      .setText(text)
      .setLinkUrl(newLink)
      .build();
    cell.setRichTextValue(newRichText);
    return;
  }

  if (useFormula === true) {
    cell.setFormula('=HYPERLINK("' + escapeFormulaString_(newLink) + '", "' + escapeFormulaString_(text) + '")');
    return;
  }

  var linkedText = SpreadsheetApp.newRichTextValue()
    .setText(text)
    .setLinkUrl(newLink)
    .build();
  cell.setRichTextValue(linkedText);
}

function productLinkApiFetch_(path, options, expectArray) {
  var apiUrl = productLinkApiBaseUrl_() + path;
  var fetchOptions = options || {};
  fetchOptions.muteHttpExceptions = true;
  fetchOptions.headers = fetchOptions.headers || {};
  fetchOptions.headers.Accept = "application/json";
  // 优先请求未压缩结果，避免跨代理压缩/解码差异。
  fetchOptions.headers["Accept-Encoding"] = "identity";

  var response = UrlFetchApp.fetch(apiUrl, fetchOptions);
  return unwrapEasyApiResponse_(response, apiUrl, expectArray);
}

function productLinkApiBaseUrl_() {
  var configured = PropertiesService.getScriptProperties().getProperty("TALENT_CRM_API_BASE_URL");
  return String(configured || PRODUCT_LINK_API_DEFAULT_BASE_URL).replace(/\/+$/, "");
}

function unwrapEasyApiResponse_(response, apiUrl, expectArray) {
  var status = response.getResponseCode();
  var transfer = readProductLinkResponse_(response, apiUrl);
  var content = transfer.text;
  var jsonText = String(content || "").replace(/^\uFEFF/, "").trim();
  var parsed = null;
  var parseFailed = false;
  var parseFailureLocation = "";

  if (jsonText) {
    try {
      parsed = JSON.parse(jsonText);
    } catch (e) {
      parseFailed = true;
      // 原始异常可能带商品正文，仅提取语法位置，不把整段响应暴露在错误提示中。
      var parseMessage = String(e && e.message || "");
      var position = parseMessage.match(/position\s+(\d+)/i);
      parseFailureLocation = position ? "JSON错误位置=" + position[1]
        : /unexpected end|unterminated|end of.*json/i.test(parseMessage) ? "JSON意外结束" : "JSON语法无效";
    }
  }

  if (status < 200 || status >= 300) {
    throw new Error("接口请求失败(" + status + ")：" + extractApiErrorMessage_(parsed, content, apiUrl));
  }

  // 不把空正文、网关网页和截断 JSON 静默变成 null，否则会误报成明细格式错误。
  if (!jsonText) {
    throw productLinkResponseError_("接口返回空内容，已停止获取结果", response, apiUrl, content, parsed, transfer);
  }
  if (parseFailed) {
    var message = /^\s*</.test(jsonText)
      ? "接口返回了网页，未收到商品明细 JSON，已停止获取结果"
      : "接口返回的 JSON 无法解析，可能未完整接收，已停止获取结果";
    transfer.parseFailureLocation = parseFailureLocation;
    throw productLinkResponseError_(message, response, apiUrl, content, parsed, transfer);
  }

  var data = parsed;
  if (parsed && (Object.prototype.hasOwnProperty.call(parsed, "success") || Object.prototype.hasOwnProperty.call(parsed, "code"))) {
    if (parsed.success === false || (parsed.code && Number(parsed.code) !== 200)) {
      throw new Error(extractApiErrorMessage_(parsed, content, apiUrl));
    }
    data = parsed.data;
  }

  if (expectArray === true && !Array.isArray(data)) {
    var actualType = data === null ? "null" : data === undefined ? "缺少 data 字段"
      : typeof data === "object" ? "对象" : typeof data === "string" ? "字符串" : typeof data;
    throw productLinkResponseError_("任务明细必须是数组，实际返回" + actualType + "，已停止获取结果",
      response, apiUrl, content, parsed, transfer);
  }
  return data;
}

function readProductLinkResponse_(response, apiUrl) {
  if (typeof response.getContent !== "function") {
    return { text: response.getContentText("UTF-8"), decoding: "UTF-8" };
  }
  var bytes = response.getContent();
  var transfer = { rawLength: bytes.length, head: productLinkBytesHex_(bytes, 0),
    tail: productLinkBytesHex_(bytes, Math.max(0, bytes.length - 4)), decoding: "UTF-8" };
  // Google Byte[] 可以包含有符号字节；已解压的 JSON 即使仍带 gzip 头也不能再次解压。
  var gzip = bytes.length >= 3 && (bytes[0] & 255) === 31 && (bytes[1] & 255) === 139 && (bytes[2] & 255) === 8;
  if (gzip) {
    transfer.decoding = "gzip + UTF-8";
    try {
      transfer.text = Utilities.ungzip(Utilities.newBlob(bytes, "application/gzip")).getDataAsString("UTF-8");
    } catch (error) {
      throw productLinkResponseError_("响应 gzip 解压失败，可能未完整接收，已停止获取结果",
        response, apiUrl, "", null, transfer);
    }
  } else {
    transfer.text = Utilities.newBlob(bytes).getDataAsString("UTF-8");
  }
  return transfer;
}

function productLinkBytesHex_(bytes, offset) {
  var hex = [];
  for (var i = offset; i < Math.min(bytes.length, offset + 4); i++) {
    hex.push(("0" + (bytes[i] & 255).toString(16)).slice(-2));
  }
  return hex.join(" ");
}

function productLinkResponseError_(message, response, apiUrl, content, parsed, transfer) {
  var headers = typeof response.getAllHeaders === "function" ? response.getAllHeaders() : {};
  function header(name) {
    var found = Object.keys(headers || {}).filter(function(key) { return key.toLowerCase() === name; })[0];
    return found ? String(headers[found]).slice(0, 160) : "";
  }
  var details = ["HTTP " + response.getResponseCode(),
    "Content-Type=" + (header("content-type") || "未提供"),
    "响应字符数=" + String(content || "").length,
    "地址=" + String(apiUrl || "").split("?")[0]];
  ["content-encoding", "content-length", "transfer-encoding"].forEach(function(name) {
    if (header(name)) details.push(name + "=" + header(name));
  });
  if (transfer) {
    if (typeof transfer.rawLength === "number") {
      details.push("原始字节数=" + transfer.rawLength);
      details.push("首字节=" + transfer.head);
      details.push("尾字节=" + transfer.tail);
    }
    if (transfer.decoding) details.push("解码=" + transfer.decoding);
    if (transfer.parseFailureLocation) details.push(transfer.parseFailureLocation);
  }
  var traceId = parsed && typeof parsed === "object" ? parsed.traceId || parsed.requestId : "";
  traceId = traceId || header("x-easy-trace-id") || header("x-request-id");
  if (traceId) details.push("请求编码=" + String(traceId).slice(0, 160));
  var ray = header("cf-ray");
  if (ray) details.push("CF-Ray=" + ray);
  return new Error(message + "（" + details.join("；") + "）");
}

function extractApiErrorMessage_(parsed, content, apiUrl) {
  if (parsed && parsed.message) {
    return parsed.message + (parsed.traceId ? "，请求编码：" + parsed.traceId : "");
  }
  if (content) {
    return content;
  }
  return apiUrl;
}

function normalizeMatchValue_(value) {
  if (value === null || value === undefined) return "";
  return String(value).trim();
}

function normalizeProductId_(value) {
  if (value === null || value === undefined) return "";
  var text = String(value).trim();
  return /^\d+$/.test(text) ? text : "";
}

function normalizeReplacementProductId_(value) {
  if (value === null || value === undefined) return "";
  return String(value).trim();
}

function escapeFormulaString_(value) {
  return String(value || "").replace(/"/g, '""');
}

function buildProductLinkFailureMessage_(failedItems) {
  var preview = failedItems.slice(0, 10).join("\n");
  if (failedItems.length > 10) {
    preview += "\n还有 " + (failedItems.length - 10) + " 个失败项未显示";
  }
  return preview;
}

function getProductLinkTemplates() {
  return fetchProductLinkTemplates_();
}

function fetchProductLinkTemplates_() {
  var templates = productLinkApiFetch_(
    "/external/product-links/templates?channelCode=" + encodeURIComponent(PRODUCT_LINK_SOURCE_CHANNEL_CODE),
    { method: "GET" }
  );
  return Array.isArray(templates) ? templates : [];
}

function productLinkTemplatesFromFormData_(formData) {
  if (formData && Array.isArray(formData.linkTemplates) && formData.linkTemplates.length > 0) {
    return formData.linkTemplates;
  }
  return fetchProductLinkTemplates_();
}

function selectedProductLinkTemplateFromFormData_(formData) {
  var templates = productLinkTemplatesFromFormData_(formData);
  if (!Array.isArray(templates) || templates.length === 0) {
    return null;
  }
  var templateId = formData ? formData.templateId : null;
  if (templateId !== null && templateId !== undefined && String(templateId) !== "") {
    for (var i = 0; i < templates.length; i++) {
      if (String(templates[i].id) === String(templateId)) {
        return templates[i];
      }
    }
  }
  var templateCode = formData ? formData.templateCode : null;
  if (templateCode) {
    for (var j = 0; j < templates.length; j++) {
      if (String(templates[j].templateCode || "").toLowerCase() === String(templateCode).toLowerCase()) {
        return templates[j];
      }
    }
  }
  return null;
}

function matchProductLinkTemplate_(originalLink, templates) {
  if (!originalLink || !Array.isArray(templates) || templates.length === 0) {
    return null;
  }

  var sortedTemplates = templates.slice().sort(function(a, b) {
    return Number(a.sortOrder || 0) - Number(b.sortOrder || 0);
  });
  var candidates = collectProductLinkCandidateUrls_(originalLink, false);
  var matched = matchProductLinkTemplateCandidates_(candidates, sortedTemplates);
  if (matched) {
    return matched;
  }

  return matchProductLinkTemplateCandidates_(collectProductLinkCandidateUrls_(originalLink, true), sortedTemplates);
}

function matchProductLinkTemplateCandidates_(candidates, templates) {
  for (var t = 0; t < templates.length; t++) {
    var template = templates[t] || {};
    for (var c = 0; c < candidates.length; c++) {
      var candidate = candidates[c];
      if (!productLinkTemplateMatchesUrl_(candidate, template)) {
        continue;
      }

      var productId = extractTemplateProductId_(candidate, template.templateUrl);
      if (!productId) {
        continue;
      }

      return {
        templateId: template.id,
        templateCode: template.templateCode,
        templateName: template.templateName,
        templateUrl: template.templateUrl,
        channelCode: template.channelCode || PRODUCT_LINK_SOURCE_CHANNEL_CODE,
        productId: productId,
        invitationCode: extractTemplateInviteCode_(candidate, template.templateUrl)
      };
    }
  }
  return null;
}

function productLinkTemplateMatchesUrl_(candidateUrl, template) {
  var templateHost = extractHostFromUrl_(template && template.templateUrl);
  if (!templateHost) {
    return false;
  }
  var candidateHost = extractHostFromUrl_(candidateUrl);
  return candidateHost ? hostMatchesPattern_(candidateHost, templateHost) : false;
}

function extractTemplateProductId_(candidateUrl, templateUrl) {
  var fromTemplate = extractProductIdByTemplatePattern_(candidateUrl, templateUrl);
  if (fromTemplate) {
    return fromTemplate;
  }
  var fromNestedUrl = extractNestedProductId_(candidateUrl);
  if (fromNestedUrl) {
    return fromNestedUrl;
  }
  return extractProductIdByCommonUrlShape_(candidateUrl);
}

function extractTemplateInviteCode_(candidateUrl, templateUrl) {
  var fromTemplate = extractPlaceholderByTemplatePrefix_(candidateUrl, templateUrl, "{INVITE_CODE}", "([^&#/?]+)");
  if (fromTemplate) {
    return fromTemplate;
  }
  var inviteParams = ["ref", "affcode", "inviteCode", "invite_code", "partnercode", "invitor_id", "invite_id", "promotionCode", "memberId", "spider_token", "from"];
  return extractFirstParamValue_(candidateUrl, inviteParams, false);
}

function extractProductIdByCommonUrlShape_(candidateUrl) {
  var variants = collectProductLinkCandidateUrls_(candidateUrl || "", false);
  var productIdParamNames = ["itemID", "itemId", "itemid", "id", "productId", "product_id", "goodsId", "goods_id"];
  for (var i = 0; i < variants.length; i++) {
    var fromParam = extractFirstParamValue_(variants[i], productIdParamNames, true);
    if (fromParam) {
      return fromParam;
    }
    var pathMatch = String(variants[i]).match(/(?:\/(?:product|goods|item)(?:\/(?:weidian|WEIDIAN|micro|\d+))?\/|item-micro-)(\d{6,})(?:[/?#.]|$)/i);
    if (pathMatch && pathMatch[1]) {
      return pathMatch[1];
    }
  }
  return "";
}

function extractProductIdByTemplatePattern_(candidateUrl, templateUrl) {
  var fromPrefix = extractPlaceholderByTemplatePrefix_(candidateUrl, templateUrl, "{PRODUCT_ID}", "(\\d{6,})");
  if (fromPrefix) {
    return fromPrefix;
  }
  var fromNestedUrl = extractNestedProductId_(candidateUrl);
  if (fromNestedUrl) {
    return fromNestedUrl;
  }
  return "";
}

function extractPlaceholderByTemplatePrefix_(candidateUrl, templateUrl, placeholder, capturePattern) {
  var variants = collectProductLinkCandidateUrls_(templateUrl || "", false);
  for (var i = 0; i < variants.length; i++) {
    var pattern = String(variants[i] || "");
    var placeholderIndex = pattern.indexOf(placeholder);
    if (placeholderIndex < 0) {
      continue;
    }
    var prefix = pattern.substring(0, placeholderIndex);
    try {
      var re = new RegExp(escapeRegExp_(prefix) + capturePattern, "i");
      var match = String(candidateUrl || "").match(re);
      if (match && match[1]) {
        return safeDecodeURIComponent_(match[1]);
      }
    } catch (err) {
      // 忽略异常模板，避免一个配置影响整个侧边栏。
    }
  }
  return "";
}

function collectProductLinkCandidateUrls_(url, includeRedirects) {
  var seen = {};
  var out = [];

  function push(value) {
    var text = String(value || "").trim();
    if (!text || seen[text]) {
      return;
    }
    seen[text] = true;
    out.push(text);
  }

  push(url);
  decodeUrlVariants_(url).forEach(push);

  if (includeRedirects) {
    var redirected = resolveRedirectChain_(url);
    redirected.forEach(function(oneUrl) {
      push(oneUrl);
      decodeUrlVariants_(oneUrl).forEach(push);
    });
  }

  return out;
}

function extractFirstParamValue_(url, names, numericOnly) {
  var paramNames = Array.isArray(names) ? names : [];
  for (var i = 0; i < paramNames.length; i++) {
    var value = extractParamValue_(url, paramNames[i]);
    if (!value) {
      continue;
    }
    if (numericOnly && !/^\d+$/.test(value)) {
      var nestedProductId = extractNestedProductId_(value);
      if (nestedProductId) {
        return nestedProductId;
      }
      continue;
    }
    return value;
  }
  return "";
}

function extractParamValue_(url, name) {
  if (!url || !name) {
    return "";
  }
  var re = new RegExp("(?:[?&#]|%3F|%26)" + escapeRegExp_(name) + "(?:=|%3D)([^&#]+)", "i");
  var match = String(url).match(re);
  if (!match || !match[1]) {
    return "";
  }
  return safeDecodeURIComponent_(match[1].replace(/\+/g, "%20"));
}

function extractNestedProductId_(value) {
  var variants = collectProductLinkCandidateUrls_(value || "", false);
  var productIdParamNames = ["itemID", "itemId", "itemid", "id", "productId", "product_id", "goodsId", "goods_id"];
  for (var i = 0; i < variants.length; i++) {
    for (var p = 0; p < productIdParamNames.length; p++) {
      var productId = extractParamValue_(variants[i], productIdParamNames[p]);
      if (productId && /^\d+$/.test(productId)) {
        return productId;
      }
    }
    var regexMatch = String(variants[i]).match(/(?:itemID|itemId|itemid|productId|product_id|goodsId|goods_id|id)(?:=|%3D)(\d{6,})/i);
    if (regexMatch && regexMatch[1]) {
      return regexMatch[1];
    }
  }
  return "";
}

function extractHostFromUrl_(url) {
  var match = String(url || "").match(/^https?:\/\/([^\/?#]+)/i);
  return match && match[1] ? match[1].toLowerCase().replace(/:\d+$/, "") : "";
}

function hostMatchesPattern_(host, pattern) {
  var normalizedHost = stripWww_(host);
  var normalizedPattern = stripWww_(pattern);
  return normalizedHost === normalizedPattern || normalizedHost.endsWith("." + normalizedPattern);
}

function stripWww_(host) {
  return String(host || "").trim().toLowerCase().replace(/^www\./, "");
}

function safeDecodeURIComponent_(value) {
  try {
    return decodeURIComponent(String(value || ""));
  } catch (err) {
    return String(value || "");
  }
}

function saveProductLinkFormDraft_(formData) {
  saveDraft("submitFromCache", sanitizeProductLinkFormData_(formData));
}

function sanitizeProductLinkFormData_(formData) {
  var copy = Object.assign({}, formData || {});
  delete copy.linkTemplates;
  delete copy.replacementSnapshot;
  return copy;
}

function persistProductLinkJobRecovery_(jobCode, formData) {
  var sanitized = sanitizeProductLinkFormData_(formData);
  sanitized.productJobCode = jobCode;
  saveDraft("submitFromCache", sanitized);
  saveDraft(PRODUCT_LINK_JOB_RECOVERY_KEY, {
    jobCode: jobCode,
    formData: sanitized,
    updatedAt: new Date().toISOString()
  });
}

function mergeProductLinkRecoveryFormData_(formData) {
  var recovery = getDraft(PRODUCT_LINK_JOB_RECOVERY_KEY);
  if (recovery && recovery.jobCode && String(recovery.jobCode) === String(formData.productJobCode || recovery.jobCode)) {
    return Object.assign({}, recovery.formData || {}, formData || {}, {
      productJobCode: formData.productJobCode || recovery.jobCode
    });
  }
  return formData;
}

function clearProductLinkJobRecovery_(jobCode) {
  var recovery = getDraft(PRODUCT_LINK_JOB_RECOVERY_KEY);
  if (recovery && String(recovery.jobCode) === String(jobCode)) {
    PropertiesService.getScriptProperties().deleteProperty(PRODUCT_LINK_JOB_RECOVERY_KEY);
  }
}


/**
 * 获取当前选择的范围A1表示法
 * @return {string} 范围的A1表示法
 */
function getSelectedRange() {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();
  var selection = sheet.getSelection();
  var range = selection.getActiveRange();
  return range.getA1Notation();
}

function getSubmitFormInitialData() {
  return getProductLinkReplaceBootstrap();
}

function getProductLinkReplaceBootstrap() {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();
  var selection = sheet.getSelection();
  var range = selection.getActiveRange();
  var templates = fetchProductLinkTemplates_();
  if (!range) {
    return {
      rangeA1Notation: "",
      cellContent: "",
      originalLink: "",
      templates: templates,
      matchedTemplate: null
    };
  }

  var firstCell = range.getCell(1, 1);
  var originalLink = getUrlFromCellData_(
    firstCell.getRichTextValue(),
    firstCell.getFormula(),
    firstCell.getDisplayValue()
  );
  return {
    rangeA1Notation: range.getA1Notation(),
    sheetId: sheet.getSheetId(),
    spreadsheetId: sheet.getParent().getId(),
    sheetName: sheet.getName(),
    cellContent: firstCell.getDisplayValue(),
    originalLink: originalLink,
    templates: templates,
    matchedTemplate: matchProductLinkTemplate_(originalLink, templates)
  };
}

// 保存草稿
function saveDraft(cacheName,formData) {
  PropertiesService.getScriptProperties().setProperty(cacheName, JSON.stringify(formData));
}

// 获取草稿
function getDraft(cacheName) {
  const draft = PropertiesService.getScriptProperties().getProperty(cacheName);
  return draft ? JSON.parse(draft) : null;
}

// 清除草稿
function clearDraft() {
  PropertiesService.getScriptProperties().deleteProperty(cacheName);
}


function convertSelectedImageFormulaToEmbedded() {
  const sheet = SpreadsheetApp.getActiveSheet();
  const range = sheet.getActiveRange();  // ✅ 只获取当前选中的范围
  const formulas = range.getFormulas();

  for (let i = 0; i < formulas.length; i++) {
    for (let j = 0; j < formulas[i].length; j++) {
      const formula = formulas[i][j];
      // 匹配 =IMAGE("url") 公式
      const match = formula.match(/^=IMAGE\("([^"]+)"(?:,\s*4,\s*(\d+),\s*(\d+))?\)/i);
      if (match) {
        const url = match[1];
        const height = match[2] ? parseInt(match[2]) : null;
        const width = match[3] ? parseInt(match[3]) : null;

        const cell = range.getCell(i+1, j+1);

        // ✅ 注意这里要用 url 变量，而不是字符串 "url"
        let imgBuilder = SpreadsheetApp.newCellImage().setSourceUrl(url);

        // 如果需要设置 alt 文本，或者将来扩展宽高，可以在这里补充
        if (height && width) {
          imgBuilder = imgBuilder.setAltTextTitle(`w:${width},h:${height}`);
        }

        const imgValue = imgBuilder.build();
		if(imgValue){
		cell.clear();
			cell.setValue(imgValue);
		}
      }
    }
  }
}


function getUiState() {
  const ss = SpreadsheetApp.getActive();
  const sheet = ss.getActiveSheet();
  const selection = SpreadsheetApp.getSelection();
  const rangeList = selection && selection.getActiveRangeList();
  const ranges = rangeList ? rangeList.getRanges().map(r => r.getA1Notation()) : [];

  return {
    sheetName: sheet.getName(),
    sheetId: sheet.getSheetId(),
    spreadsheetId: ss.getId(),
    selectedRanges: ranges,
    lastRow: sheet.getLastRow(),
    timestamp: Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'yyyyMMdd_HHmmss')
  };
}

function extractIdsFromLinks(payload) {
  payload = payload || {};

  const ss = SpreadsheetApp.getActive();
  const sheet = payload.sheetName
    ? ss.getSheetByName(String(payload.sheetName))
    : ss.getActiveSheet();

  if (!sheet) {
    throw new Error('找不到工作表。');
  }

  const sourceColumn = columnToIndex_(payload.sourceColumn);
  const targetColumn = columnToIndex_(payload.targetColumn);
  const startRow = Math.max(1, Number(payload.startRow) || 2);
  const lastRowOnSheet = sheet.getLastRow();
  const endRow = Math.min(Number(payload.endRow) || lastRowOnSheet, lastRowOnSheet);

  if (endRow < startRow) {
    throw new Error('结束行不能小于开始行。');
  }

  const numRows = endRow - startRow + 1;
  const range = sheet.getRange(startRow, sourceColumn, numRows, 1);
  const richTexts = range.getRichTextValues();
  const formulas = range.getFormulas();
  const displayValues = range.getDisplayValues();

  const results = [];
  let foundCount = 0;
  let urlCount = 0;

  for (let i = 0; i < numRows; i++) {
    const richText = richTexts[i][0];
    const formula = formulas[i][0];
    const displayValue = displayValues[i][0];
    const url = getUrlFromCellData_(richText, formula, displayValue);

    if (url) {
      urlCount += 1;
    }

    const productId = extractNumericIdFromUrl_(url, payload.paramNames);
    if (productId) {
      foundCount += 1;
      results.push([productId]);
    } else {
      results.push(['']);
    }
  }

  sheet.getRange(startRow, targetColumn, numRows, 1).setValues(results);

  return {
    ok: true,
    sheetName: sheet.getName(),
    sourceColumn: indexToColumn_(sourceColumn),
    targetColumn: indexToColumn_(targetColumn),
    startRow,
    endRow,
    scannedRows: numRows,
    urlCount,
    foundCount,
    writtenRange: `${indexToColumn_(targetColumn)}${startRow}:${indexToColumn_(targetColumn)}${endRow}`
  };
}

function exportSelectedCellImages(payload) {
  payload = payload || {};

  const selection = SpreadsheetApp.getSelection();
  const rangeList = selection && selection.getActiveRangeList();

  if (!rangeList) {
    throw new Error('请先选中包含图片的单元格。');
  }

  const ranges = rangeList.getRanges();
  if (!ranges.length) {
    throw new Error('请先选中包含图片的单元格。');
  }

  const timestamp = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'yyyyMMdd_HHmmss');
  const folderPrefix = sanitizeFileName_(payload.folderName || 'Sheet_Images');
  const zipBaseName = sanitizeFileName_(payload.zipName || `images_${timestamp}`);
  const folder = DriveApp.createFolder(`${folderPrefix}_${timestamp}`);

  const blobsForZip = [];
  const items = [];
  const skipped = [];

  ranges.forEach(range => {
    const sheet = range.getSheet();
    const values = range.getValues();
    const formulas = range.getFormulas();

    for (let r = 0; r < values.length; r++) {
      for (let c = 0; c < values[r].length; c++) {
        const absoluteRow = range.getRow() + r;
        const absoluteCol = range.getColumn() + c;
        const a1 = sheet.getRange(absoluteRow, absoluteCol).getA1Notation();

        try {
          const blob = getImageBlobFromCell_(values[r][c], formulas[r][c]);
          if (!blob) {
            continue;
          }

          const ext = guessExtensionFromMime_(blob.getContentType());
          const filename = `${sanitizeFileName_(sheet.getName())}_${a1}.${ext}`;
          const namedBlob = blob.copyBlob().setName(filename);

          folder.createFile(namedBlob.copyBlob());
          blobsForZip.push(namedBlob.copyBlob());
          items.push({
            sheetName: sheet.getName(),
            cell: a1,
            fileName: filename
          });
        } catch (err) {
          skipped.push({
            sheetName: sheet.getName(),
            cell: a1,
            reason: String(err && err.message ? err.message : err)
          });
        }
      }
    }
  });

  if (!blobsForZip.length) {
    throw new Error('所选区域里没有找到可导出的单元格图片。请确认这些图片是”插入到单元格中”的图片，或是 =IMAGE(“...”) 公式。');
  }

  const zipName = zipBaseName.toLowerCase().endsWith('.zip') ? zipBaseName : `${zipBaseName}.zip`;
  const zipFile = folder.createFile(Utilities.zip(blobsForZip, zipName));

  return {
    ok: true,
    folderName: folder.getName(),
    folderUrl: folder.getUrl(),
    zipName: zipFile.getName(),
    zipUrl: zipFile.getUrl(),
    count: items.length,
    selectedRanges: ranges.map(r => r.getA1Notation()),
    previewItems: items.slice(0, 20),
    skipped: skipped.slice(0, 20)
  };
}

function getUrlFromCellData_(richText, formula, displayValue) {
  if (richText) {
    const oneLink = richText.getLinkUrl();
    if (oneLink) {
      return oneLink;
    }

    const runs = richText.getRuns ? richText.getRuns() : [];
    for (let i = 0; i < runs.length; i++) {
      const link = runs[i].getLinkUrl();
      if (link) {
        return link;
      }
    }
  }

  if (formula) {
    const hyperlinkMatch = formula.match(/^=HYPERLINK\(\s*"((?:[^"]|"")+)"/i);
    if (hyperlinkMatch && hyperlinkMatch[1]) {
      return hyperlinkMatch[1].replace(/""/g, '"');
    }
  }

  const text = String(displayValue || '').trim();
  if (/^https?:\/\//i.test(text)) {
    return text;
  }

  return '';
}

function extractNumericIdFromUrl_(url, paramNamesCsv) {
  if (!url) {
    return '';
  }

  const paramNames = normalizeParamNames_(paramNamesCsv);
  const candidates = collectCandidateUrls_(url);

  for (let i = 0; i < candidates.length; i++) {
    const id = extractIdFromSingleUrl_(candidates[i], paramNames);
    if (id) {
      return id;
    }
  }

  return '';
}

function normalizeParamNames_(paramNamesCsv) {
  return String(paramNamesCsv || 'itemID,itemId,itemid,id')
    .split(',')
    .map(s => s.trim())
    .filter(Boolean);
}

function collectCandidateUrls_(url) {
  const seen = {};
  const out = [];

  function pushOnce(value) {
    const text = String(value || '').trim();
    if (!text || seen[text]) {
      return;
    }
    seen[text] = true;
    out.push(text);
  }

  pushOnce(url);

  const decodedVariants = decodeUrlVariants_(url);
  decodedVariants.forEach(pushOnce);

  const redirected = resolveRedirectChain_(url);
  redirected.forEach(pushOnce);

  redirected.forEach(function(oneUrl) {
    decodeUrlVariants_(oneUrl).forEach(pushOnce);
  });

  return out;
}

function extractIdFromSingleUrl_(url, paramNames) {
  if (!url) {
    return '';
  }

  const text = String(url);

  for (let i = 0; i < paramNames.length; i++) {
    const name = paramNames[i];
    const re = new RegExp('(?:[?&]|%3F|%26)' + escapeRegExp_(name) + '(?:=|%3D)(\\d+)(?=&|$|%26)', 'i');
    const match = text.match(re);
    if (match) {
      return match[1];
    }
  }

  const genericMatch = text.match(/(?:[?&]|%3F|%26)[A-Za-z_][A-Za-z0-9_]*(?:=|%3D)(\d+)(?=&|$|%26)/i);
  return genericMatch ? genericMatch[1] : '';
}

function decodeUrlVariants_(url) {
  const out = [];
  let current = String(url || '').trim();

  for (let i = 0; i < MAX_DECODE_PASSES; i++) {
    try {
      const next = decodeURIComponent(current);
      if (!next || next === current) {
        break;
      }
      out.push(next);
      current = next;
    } catch (err) {
      break;
    }
  }

  return out;
}

function resolveRedirectChain_(startUrl) {
  const chain = [];
  let current = String(startUrl || '').trim();

  if (!current) {
    return chain;
  }

  for (let i = 0; i < MAX_REDIRECT_DEPTH; i++) {
    try {
      const nextUrl = resolveNextHop_(current);
      if (!nextUrl || nextUrl === current) {
        break;
      }
      chain.push(nextUrl);
      current = nextUrl;
    } catch (err) {
      break;
    }
  }

  return chain;
}

function resolveNextHop_(url) {
  const response = UrlFetchApp.fetch(url, {
    muteHttpExceptions: true,
    followRedirects: false,
    headers: {
      'User-Agent': 'Mozilla/5.0 (compatible; GoogleAppsScript/1.0)'
    }
  });

  const code = response.getResponseCode();
  const headers = response.getHeaders ? response.getHeaders() : {};
  const location = getHeaderIgnoreCase_(headers, 'Location');

  if (location && code >= 300 && code < 400) {
    return buildAbsoluteUrl_(url, location);
  }

  const html = safeResponseText_(response);
  if (!html) {
    return '';
  }

  const meta = extractMetaRefreshUrl_(html);
  if (meta) {
    return buildAbsoluteUrl_(url, meta);
  }

  const scriptRedirect = extractScriptRedirectUrl_(html);
  if (scriptRedirect) {
    return buildAbsoluteUrl_(url, scriptRedirect);
  }

  return '';
}

function safeResponseText_(response) {
  try {
    return response.getContentText();
  } catch (err) {
    return '';
  }
}

function extractMetaRefreshUrl_(html) {
  const text = String(html || '');
  const match = text.match(/<meta[^>]+http-equiv=["']?refresh["']?[^>]+content=["'][^"']*url=([^"'>]+)["']/i);
  return match && match[1] ? htmlDecode_(match[1].trim()) : '';
}

function extractScriptRedirectUrl_(html) {
  const text = String(html || '');
  const patterns = [
    /(?:window\.|document\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']/i,
    /location\.replace\(\s*["']([^"']+)["']\s*\)/i,
    /location\.assign\(\s*["']([^"']+)["']\s*\)/i
  ];

  for (let i = 0; i < patterns.length; i++) {
    const match = text.match(patterns[i]);
    if (match && match[1]) {
      return htmlDecode_(match[1].trim());
    }
  }

  return '';
}

function htmlDecode_(text) {
  return String(text || '')
    .replace(/&amp;/gi, '&')
    .replace(/&#x3D;/gi, '=')
    .replace(/&#61;/gi, '=')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'");
}

function getHeaderIgnoreCase_(headers, wantedName) {
  const target = String(wantedName || '').toLowerCase();
  const keys = Object.keys(headers || {});
  for (let i = 0; i < keys.length; i++) {
    if (String(keys[i]).toLowerCase() === target) {
      return headers[keys[i]];
    }
  }
  return '';
}

function buildAbsoluteUrl_(baseUrl, maybeRelative) {
  const target = String(maybeRelative || '').trim();
  if (!target) {
    return '';
  }
  if (/^https?:\/\//i.test(target)) {
    return target;
  }
  if (/^\/\//.test(target)) {
    const protocol = String(baseUrl || '').toLowerCase().indexOf('https://') === 0 ? 'https:' : 'http:';
    return protocol + target;
  }

  const base = String(baseUrl || '');
  const originMatch = base.match(/^(https?:\/\/[^\/]+)/i);
  if (!originMatch) {
    return target;
  }
  const origin = originMatch[1];

  if (target.charAt(0) === '/') {
    return origin + target;
  }

  const normalizedBase = base.replace(/[?#].*$/, '');
  const lastSlash = normalizedBase.lastIndexOf('/');
  const dir = lastSlash >= 0 ? normalizedBase.slice(0, lastSlash + 1) : origin + '/';
  return dir + target;
}

function getImageBlobFromCell_(cellValue, formula) {
  if (cellValue && typeof cellValue.getContentUrl === 'function') {
    const contentUrl = cellValue.getContentUrl();
    if (contentUrl) {
      return fetchImageBlob_(contentUrl);
    }
  }

  const formulaImageUrl = parseImageFormulaUrl_(formula);
  if (formulaImageUrl) {
    return fetchImageBlob_(formulaImageUrl);
  }

  return null;
}

function parseImageFormulaUrl_(formula) {
  if (!formula) {
    return '';
  }

  const match = String(formula).match(/^=IMAGE\(\s*"((?:[^"]|"")+)"/i);
  return match && match[1] ? match[1].replace(/""/g, '"') : '';
}

function fetchImageBlob_(url) {
  const response = UrlFetchApp.fetch(url, {
    muteHttpExceptions: true,
    followRedirects: true,
    headers: {
      'User-Agent': 'Mozilla/5.0 (compatible; GoogleAppsScript/1.0)'
    }
  });

  const code = response.getResponseCode();
  if (code < 200 || code >= 300) {
    throw new Error(`下载失败，HTTP ${code}`);
  }

  return response.getBlob();
}

function columnToIndex_(input) {
  const value = String(input || '').trim();
  if (!value) {
    throw new Error('列不能为空。');
  }

  if (/^\d+$/.test(value)) {
    const index = Number(value);
    if (index < 1) {
      throw new Error('列号必须大于 0。');
    }
    return index;
  }

  const letters = value.toUpperCase().replace(/[^A-Z]/g, '');
  if (!letters) {
    throw new Error(`无法识别列：${input}`);
  }

  let result = 0;
  for (let i = 0; i < letters.length; i++) {
    result = result * 26 + (letters.charCodeAt(i) - 64);
  }
  return result;
}

function indexToColumn_(index) {
  let n = Number(index);
  let result = '';
  while (n > 0) {
    const remainder = (n - 1) % 26;
    result = String.fromCharCode(65 + remainder) + result;
    n = Math.floor((n - 1) / 26);
  }
  return result;
}

function escapeRegExp_(text) {
  return String(text).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function sanitizeFileName_(name) {
  return String(name || 'file')
    .trim()
    .replace(/[\\/:*?"<>|#%&{}~]/g, '_')
    .replace(/\s+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 120) || 'file';
}

function guessExtensionFromMime_(mimeType) {
  const mime = String(mimeType || '').toLowerCase();
  if (mime.indexOf('png') !== -1) return 'png';
  if (mime.indexOf('jpeg') !== -1 || mime.indexOf('jpg') !== -1) return 'jpg';
  if (mime.indexOf('gif') !== -1) return 'gif';
  if (mime.indexOf('webp') !== -1) return 'webp';
  if (mime.indexOf('svg') !== -1) return 'svg';
  if (mime.indexOf('bmp') !== -1) return 'bmp';
  return 'bin';
}

// ===== 微店原始链接：只有打开弹窗时加载模板，解析及写入均不请求业务接口 =====
function showWeidianOriginalForm() {
  SpreadsheetApp.getUi().showModelessDialog(
    HtmlService.createHtmlOutputFromFile('weidianOriginalForm').setWidth(620).setHeight(680),
    '转换为微店原始链接'
  );
}

function getWeidianOriginalBootstrap() {
  var selection = getWeidianOriginalSelection();
  var templates = [];
  var templateError = '';
  try {
    templates = fetchProductLinkTemplates_().filter(function(template) {
      return template && (!template.channelCode || String(template.channelCode).toUpperCase() === 'WEIDIAN');
    });
  } catch (err) {
    templateError = '模板加载失败：' + err.message;
  }
  return { selection: selection, templates: templates, templateError: templateError };
}

function getWeidianOriginalSelection() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getActiveSheet();
  var selection = SpreadsheetApp.getSelection();
  var rangeList = selection && selection.getActiveRangeList();
  var ranges = rangeList ? rangeList.getRanges() : [];
  if (!ranges.length) throw new Error('请先选择要转换的单元格。');
  var rangeNames = ranges.map(function(range) { return range.getA1Notation(); });
  var cells = readWeidianOriginalCells_(sheet, rangeNames);
  var sample = cells.filter(function(cell) { return cell.url; })[0];
  return {
    sheetId: sheet.getSheetId(),
    sheetName: sheet.getName(),
    ranges: rangeNames,
    firstCell: sample ? sample.a1 : ranges[0].getCell(1, 1).getA1Notation(),
    firstUrl: sample ? sample.url : '',
    cellCount: cells.length
  };
}

function previewWeidianOriginalLink(payload) {
  payload = payload || {};
  return resolveWeidianOriginalLink_(payload.url, payload.templates, payload.templateIndex);
}

function weidianOriginalDecode_(value) {
  var text = htmlDecode_(String(value || '').trim());
  var variants = decodeUrlVariants_(text);
  return variants.length ? variants[variants.length - 1] : text;
}

// 保留每层参数边界再解码，避免把内层 URL 的参数误当成外层参数。
function weidianOriginalUrlParts_(value) {
  var match = String(value || '').trim().match(/^https?:\/\/([^/?#]+)([\s\S]*)$/i);
  if (!match || !/^[a-z0-9.-]+(?::\d+)?$/i.test(match[1])) return null;
  var rest = match[2];
  var question = rest.indexOf('?');
  var path = question < 0 ? rest : rest.substring(0, question);
  var query = question < 0 ? '' : rest.substring(question + 1).split('#')[0];
  // 普通锚点不影响匹配；保留 Fishgoo 等站点的 #/product 路由。
  if (path.indexOf('#/') < 0) path = path.split('#')[0];
  path = weidianOriginalDecode_(path).replace(/\/+$/, '') || '/';
  var params = [];
  query.split('&').forEach(function(pair) {
    if (!pair) return;
    var equal = pair.indexOf('=');
    params.push({
      name: weidianOriginalDecode_(equal < 0 ? pair : pair.substring(0, equal)),
      value: equal < 0 ? '' : pair.substring(equal + 1)
    });
  });
  return { host: match[1].toLowerCase().replace(/:\d+$/, '').replace(/^www\./, ''), path: path, params: params };
}

function weidianOriginalCandidates_(value) {
  var candidates = [];
  var seen = Object.create(null);
  function add(text) {
    text = htmlDecode_(String(text || '').trim());
    // 一旦成为完整 URL 就停止整串解码，保留参数值中的 %26 / %3D。
    for (var pass = 0; pass <= MAX_DECODE_PASSES; pass++) {
      if (/^https?:\/\//i.test(text)) {
        if (!seen[text] && candidates.length < 80) {
          seen[text] = true;
          candidates.push(text);
        }
        return;
      }
      try {
        var decoded = decodeURIComponent(text);
        if (decoded === text) return;
        text = decoded;
      } catch (err) {
        return;
      }
    }
  }
  add(value);
  for (var i = 0; i < candidates.length; i++) {
    var parts = weidianOriginalUrlParts_(candidates[i]);
    if (!parts) continue;
    parts.params.forEach(function(param) {
      add(param.value);
    });
  }
  return candidates;
}

function weidianOriginalParamValues_(parts, name) {
  return parts.params.filter(function(param) {
    return param.name.toLowerCase() === String(name).toLowerCase();
  }).map(function(param) { return weidianOriginalDecode_(param.value); });
}

function weidianOriginalNonWeidian_(parts) {
  if (/(^|\.)(taobao|tmall|1688)\.com$/i.test(parts.host)) return true;
  return parts.params.some(function(param) {
    return /^(shop_type|platform|source|channel|channelCode)$/i.test(param.name)
      && /^(taobao|tb|tmall|1688|ali|ali_1688)$/i.test(weidianOriginalDecode_(param.value));
  });
}

// 模板里的其他占位符可变；只捕获 PRODUCT_ID，且必须完整匹配数字边界。
function weidianOriginalMatchPart_(value, pattern) {
  var placeholders = [];
  var cursor = 0;
  var regex = '';
  var token = /\{([A-Z_]+)\}/gi;
  var match;
  while ((match = token.exec(pattern))) {
    regex += escapeRegExp_(pattern.substring(cursor, match.index));
    regex += match[1].toUpperCase() === 'PRODUCT_ID' ? '(\\d+)' : '([^/?&#]*)';
    placeholders.push(match[1].toUpperCase());
    cursor = match.index + match[0].length;
  }
  regex += escapeRegExp_(pattern.substring(cursor));
  var result = String(value).match(new RegExp('^' + regex + '$', 'i'));
  if (!result) return null;
  var ids = [];
  placeholders.forEach(function(name, index) {
    if (name === 'PRODUCT_ID') ids.push(result[index + 1]);
  });
  return ids;
}

function weidianOriginalMatchTemplate_(candidate, template, manual) {
  var actual = weidianOriginalUrlParts_(candidate);
  var expected = weidianOriginalUrlParts_(weidianOriginalCandidates_(template.templateUrl)[0]);
  if (!actual || !expected || weidianOriginalNonWeidian_(actual) || weidianOriginalNonWeidian_(expected)) return null;
  if (!manual && actual.host !== expected.host) return null;
  var ids = weidianOriginalMatchPart_(actual.path, expected.path);
  if (!ids) return null;
  for (var i = 0; i < expected.params.length; i++) {
    var param = expected.params[i];
    var pattern = weidianOriginalDecode_(param.value);
    var hasProductId = /\{PRODUCT_ID\}/i.test(pattern);
    // 邀请码、推广参数以及其固定值均不参与来源识别。
    if (!hasProductId && (
      /\{[A-Z_]+\}/i.test(pattern)
      || /^(ref|affcode|inviteCode|invite_code|partnercode|invitor_id|invite_id|promotionCode|memberId|spider_token|from|u|invitation_code|nTag|_search|position|searchlang|utm_.+)$/i.test(param.name)
    )) continue;
    var values = weidianOriginalParamValues_(actual, param.name);
    if (!values.length) return null;
    for (var j = 0; j < values.length; j++) {
      var matchedIds = weidianOriginalMatchPart_(values[j], pattern);
      if (!matchedIds) return null;
      ids = ids.concat(matchedIds);
    }
  }
  return ids.length ? ids : null;
}

function resolveWeidianOriginalLink_(url, templates, templateIndex) {
  function failure(reason) { return { ok: false, reason: reason }; }
  if (!url) return failure('未找到可读取的链接。');
  templates = Array.isArray(templates) ? templates : [];
  var manual = templateIndex !== null && templateIndex !== undefined && templateIndex !== '';
  if (manual && (!Number.isInteger(Number(templateIndex)) || Number(templateIndex) < 0 || !templates[Number(templateIndex)])) {
    return failure('所选模板已失效，请重新加载模板。');
  }
  var candidates = weidianOriginalCandidates_(url);
  var originals = [];
  var invalidOriginal = false;
  candidates.forEach(function(candidate) {
    var parts = weidianOriginalUrlParts_(candidate);
    if (!parts || !/(^|\.)weidian\.com$/i.test(parts.host) || !/^\/item\.html$/i.test(parts.path)) return;
    var ids = weidianOriginalParamValues_(parts, 'itemID');
    ids.forEach(function(id) {
      if (!/^\d+$/.test(id)) {
        invalidOriginal = true;
      } else {
        originals.push({ productId: id, url: candidate, templateName: '微店原始链接' });
      }
    });
  });
  var matches = originals;
  if (invalidOriginal) return failure('微店链接中的 itemID 不是有效的完整数字。');
  // 完整原始链接优先提取，保留其已有参数；无需模板也可处理编码后的原始链接。
  if (!matches.length) {
    var selected = manual ? [templates[Number(templateIndex)]] : templates;
    selected.forEach(function(template) {
      if (!template || !template.templateUrl || (template.channelCode && String(template.channelCode).toUpperCase() !== 'WEIDIAN')) return;
      candidates.forEach(function(candidate) {
        var ids = weidianOriginalMatchTemplate_(candidate, template, manual);
        (ids || []).forEach(function(id) {
          matches.push({
            productId: id,
            url: 'https://weidian.com/item.html?itemID=' + id,
            templateName: template.templateName || template.templateCode || '指定模板'
          });
        });
      });
    });
  }
  var distinctIds = [];
  matches.forEach(function(item) {
    if (distinctIds.indexOf(item.productId) < 0) distinctIds.push(item.productId);
  });
  if (distinctIds.length > 1) return failure('链接中识别到多个不同商品 ID，无法确定要转换的商品。');
  if (!matches.length) {
    return failure(manual
      ? '链接不符合所选模板，或不包含微店商品 ID。'
      : '未识别到微店原始链接或匹配模板，请手动选择来源模板；短链接需先提供完整链接。');
  }
  return { ok: true, url: matches[0].url, productId: matches[0].productId, templateName: matches[0].templateName, reason: '' };
}

function weidianOriginalCellLink_(richText, formula, displayValue) {
  var links = [];
  if (richText) {
    var runs = richText.getRuns ? richText.getRuns() : [];
    runs.forEach(function(run) {
      var link = run.getLinkUrl();
      if (link && links.indexOf(link) < 0) links.push(link);
    });
  }
  if (links.length > 1) return { url: '', reason: '单元格中有多个不同链接，请只保留需要转换的链接。' };
  var url = getUrlFromCellData_(richText, formula, displayValue);
  if (!url && /^https?:\/\//i.test(weidianOriginalDecode_(displayValue))) {
    url = String(displayValue).trim();
  }
  return { url: url, reason: url ? '' : '空单元格或未找到可读取的链接。' };
}

function readWeidianOriginalCells_(sheet, rangeNames) {
  if (!Array.isArray(rangeNames) || !rangeNames.length) throw new Error('请先选择要转换的单元格。');
  var lastRow = sheet.getLastRow();
  var lastColumn = sheet.getLastColumn();
  var seen = Object.create(null);
  var cells = [];
  var total = 0;
  var ranges = rangeNames.map(function(name) {
    if (typeof name !== 'string' || name.indexOf('!') >= 0) throw new Error('选区无效，请刷新选区。');
    var range = sheet.getRange(name);
    var rows = Math.min(range.getNumRows(), lastRow - range.getRow() + 1);
    var columns = Math.min(range.getNumColumns(), lastColumn - range.getColumn() + 1);
    if (rows <= 0 || columns <= 0) return null;
    total += rows * columns;
    if (total > 20000) throw new Error('选区过大，请每次选择不超过 20000 个已用区域内的单元格。');
    return sheet.getRange(range.getRow(), range.getColumn(), rows, columns);
  });
  ranges.forEach(function(range) {
    if (!range) return;
    var values = range.getDisplayValues();
    var richTexts = range.getRichTextValues();
    var formulas = range.getFormulas();
    for (var r = 0; r < values.length; r++) {
      for (var c = 0; c < values[r].length; c++) {
        var row = range.getRow() + r;
        var column = range.getColumn() + c;
        var key = row + ':' + column;
        if (seen[key]) continue;
        seen[key] = true;
        var link = weidianOriginalCellLink_(richTexts[r][c], formulas[r][c], values[r][c]);
        cells.push({ row: row, column: column, key: key, a1: indexToColumn_(column) + row, url: link.url, reason: link.reason });
      }
    }
  });
  return cells;
}

function convertSelectedToWeidianOriginal(payload) {
  payload = payload || {};
  if (payload.direction !== 'left' && payload.direction !== 'right') throw new Error('请选择前一列或后一列。');
  if (payload.sheetId === null || payload.sheetId === undefined || !Number.isInteger(Number(payload.sheetId))) {
    throw new Error('工作表信息无效，请刷新选区。');
  }
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetById(Number(payload.sheetId));
  if (!sheet) throw new Error('原工作表已不存在，请刷新选区。');
  var lock = LockService.getDocumentLock() || LockService.getScriptLock();
  if (!lock.tryLock(1000)) throw new Error('另一个转换正在运行，请稍后重试。');
  try {
    // 在任何写入之前一次性读取所有源格，且不从当前活动工作表重新取得选区。
    var cells = readWeidianOriginalCells_(sheet, payload.ranges);
    // 重叠判断保留完整选区边界，包括已用范围外的空白格。
    var sourceBounds = payload.ranges.map(function(name) {
      var range = sheet.getRange(name);
      return { row: range.getRow(), column: range.getColumn(), lastRow: range.getLastRow(), lastColumn: range.getLastColumn() };
    });
    var parsedUrls = Object.create(null);
    var deadline = Date.now() + 240000;
    var maxColumns = sheet.getMaxColumns();
    var result = { successCount: 0, skippedCount: 0, failedCount: 0, scannedCount: cells.length, items: [] };
    function record(cell, target, status, message) {
      result[status === 'success' ? 'successCount' : status === 'failed' ? 'failedCount' : 'skippedCount']++;
      result.items.push({ source: cell.a1, target: target, status: status, message: message });
    }
    cells.forEach(function(cell) {
      var targetColumn = cell.column + (payload.direction === 'left' ? -1 : 1);
      var targetA1 = targetColumn > 0 && targetColumn <= maxColumns ? indexToColumn_(targetColumn) + cell.row : '';
      if (Date.now() >= deadline) {
        record(cell, targetA1, 'skipped', '本次处理时间已达上限，请选择剩余单元格继续转换。');
        return;
      }
      if (!cell.url) {
        record(cell, targetA1, 'skipped', cell.reason);
        return;
      }
      if (!targetA1) {
        record(cell, '', 'skipped', '所选方向没有相邻列，请调整方向或先在表格中添加列。');
        return;
      }
      if (sourceBounds.some(function(bounds) {
        return cell.row >= bounds.row && cell.row <= bounds.lastRow && targetColumn >= bounds.column && targetColumn <= bounds.lastColumn;
      })) {
        record(cell, targetA1, 'skipped', '目标格也在源选区中，请分列选择，避免覆盖源链接。');
        return;
      }
      try {
        var source = sheet.getRange(cell.row, cell.column);
        var target = sheet.getRange(cell.row, targetColumn);
        if (source.isPartOfMerge() || target.isPartOfMerge()) {
          record(cell, targetA1, 'skipped', '源格或目标格属于合并单元格。');
          return;
        }
        if (!target.canEdit()) {
          record(cell, targetA1, 'skipped', '目标格受保护，没有编辑权限。');
          return;
        }
        if (payload.overwrite !== true && !target.isBlank()) {
          record(cell, targetA1, 'skipped', '目标格已有内容，未勾选覆盖。');
          return;
        }
        if (!parsedUrls[cell.url]) {
          parsedUrls[cell.url] = resolveWeidianOriginalLink_(cell.url, payload.templates, payload.templateIndex);
        }
        var parsed = parsedUrls[cell.url];
        if (!parsed.ok) {
          record(cell, targetA1, 'failed', parsed.reason);
          return;
        }
        target.setRichTextValue(SpreadsheetApp.newRichTextValue().setText('LINK').setLinkUrl(parsed.url).build());
        record(cell, targetA1, 'success', parsed.templateName);
      } catch (err) {
        record(cell, targetA1, 'failed', err.message || String(err));
      }
    });
    SpreadsheetApp.flush();
    result.message = '转换完成：成功 ' + result.successCount + ' 个，跳过 ' + result.skippedCount + ' 个，失败 ' + result.failedCount + ' 个。';
    // 优先展示未成功的格子，让大选区中的问题不会被成功记录淹没。
    var issues = result.items.filter(function(item) { return item.status !== 'success'; });
    var successes = result.items.filter(function(item) { return item.status === 'success'; });
    result.detailsTruncated = result.items.length > 200;
    result.items = issues.concat(successes).slice(0, 200);
    return result;
  } finally {
    lock.releaseLock();
  }
}

// ===== buy 链接互转：复用已有模板，分批处理开始时选中的区域 =====
function showBuyLinkConvertForm() {
  SpreadsheetApp.getUi().showModelessDialog(
    HtmlService.createHtmlOutputFromFile('buyLinkConvertForm').setWidth(660).setHeight(740),
    'buy 链接互转（选区）'
  );
}

// 从矩形中扣除已覆盖部分，避免重叠选区重复读写；不展开为逐单元格列表。
function buyLinkSubtractBlock_(block, covered) {
  var top = Math.max(block.row, covered.row);
  var left = Math.max(block.column, covered.column);
  var bottom = Math.min(block.row + block.rowCount, covered.row + covered.rowCount);
  var right = Math.min(block.column + block.columnCount, covered.column + covered.columnCount);
  if (top >= bottom || left >= right) return [block];
  var parts = [];
  function add(row, column, rows, columns) {
    if (rows > 0 && columns > 0) parts.push({ row: row, column: column, rowCount: rows, columnCount: columns });
  }
  add(block.row, block.column, top - block.row, block.columnCount);
  add(bottom, block.column, block.row + block.rowCount - bottom, block.columnCount);
  add(top, block.column, bottom - top, left - block.column);
  add(top, right, bottom - top, block.column + block.columnCount - right);
  return parts;
}

function getBuyLinkSelection() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getActiveSheet();
  var selection = SpreadsheetApp.getSelection();
  var rangeList = selection && selection.getActiveRangeList();
  var ranges = rangeList ? rangeList.getRanges() : [];
  if (!ranges.length && selection && selection.getActiveRange) {
    var activeRange = selection.getActiveRange();
    if (activeRange) ranges = [activeRange];
  }
  if (!sheet || !ranges.length) throw new Error('请先选择要转换的单元格区域。');
  var maxRows = sheet.getMaxRows();
  var maxColumns = sheet.getMaxColumns();
  var blocks = [];
  var names = [];
  ranges.forEach(function(range) {
    if (range.getSheet().getSheetId() !== sheet.getSheetId()) throw new Error('选区所属工作表已改变，请重新选择。');
    var block = { row: range.getRow(), column: range.getColumn(), rowCount: range.getNumRows(), columnCount: range.getNumColumns() };
    if (![block.row, block.column, block.rowCount, block.columnCount].every(function(value) { return Number.isInteger(value) && value > 0; })
      || block.row + block.rowCount - 1 > maxRows || block.column + block.columnCount - 1 > maxColumns) {
      throw new Error('选区范围无效，请重新选择。');
    }
    var name = range.getA1Notation();
    if (names.indexOf(name) < 0) names.push(name);
    var pending = [block];
    blocks.forEach(function(covered) {
      var remaining = [];
      pending.forEach(function(part) { remaining = remaining.concat(buyLinkSubtractBlock_(part, covered)); });
      pending = remaining;
    });
    blocks = blocks.concat(pending);
  });
  blocks.forEach(function(block) {
    var first = indexToColumn_(block.column) + block.row;
    var last = indexToColumn_(block.column + block.columnCount - 1) + (block.row + block.rowCount - 1);
    block.a1 = first === last ? first : first + ':' + last;
  });
  return {
    spreadsheetId: ss.getId(), spreadsheetName: ss.getName(), sheetId: sheet.getSheetId(), sheetName: sheet.getName(),
    ranges: names, blocks: blocks, maxRows: maxRows, maxColumns: maxColumns,
    cellCount: blocks.reduce(function(total, block) { return total + block.rowCount * block.columnCount; }, 0)
  };
}

function getBuyLinkConvertBootstrap() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var selection = null;
  var selectionError = '';
  try { selection = getBuyLinkSelection(); } catch (err) { selectionError = err.message; }
  var templates = [];
  var templateError = '';
  try {
    // 复用现有目标链接模板；同一份配置同时用于反向解析和目标链接生成。
    templates = getProductLinkTemplates().filter(function(template) {
      return template && (!template.channelCode || String(template.channelCode).toUpperCase() === 'WEIDIAN')
        && !buyLinkIsWeidianUrl_(template.templateUrl);
    });
  } catch (err) {
    templateError = '模板加载失败：' + err.message;
  }
  var targetOptions = [];
  templates.forEach(function(template, index) {
    try {
      buyLinkValidateTarget_(templates, index, '');
      targetOptions.push({ index: index, name: template.templateName || template.templateCode || ('模板 ' + (index + 1)) });
    } catch (err) {
      // 不把微店原始链接或无法生成的配置作为目标 buy 提供。
    }
  });
  return {
    spreadsheetId: ss.getId(), spreadsheetName: ss.getName(), selection: selection, selectionError: selectionError,
    templates: templates, targetOptions: targetOptions, templateError: templateError
  };
}

function buyLinkInvitationParam_(name) {
  // from/u 只有包含 INVITE_CODE 占位符时才算邀请码，保留 from=search-input 等正常参数。
  return /^(ref|affcode|inviteCode|invite_code|partnercode|invitor_id|invite_id|promotionCode|memberId|spider_token|invitation_code|referral_code)$/i.test(name);
}

function buyLinkIgnoreSourceParam_(name, pattern) {
  return !/\{PRODUCT_ID\}/i.test(pattern) && (
    /\{INVITE_CODE\}/i.test(pattern) || buyLinkInvitationParam_(name)
    || /^(from|u|nTag|_search|position|searchlang|utm_.+)$/i.test(name)
  );
}

// 仅解开包住整个 URL 的编码层，保留其中每个参数的编码边界。
function buyLinkUnwrapUrl_(value) {
  var text = htmlDecode_(String(value || '').trim());
  var depth = 0;
  while (!/^https?:\/\//i.test(text) && depth < MAX_DECODE_PASSES) {
    try {
      var decoded = decodeURIComponent(text);
      if (decoded === text) break;
      text = decoded;
      depth++;
    } catch (err) { break; }
  }
  return { url: text, depth: depth };
}

function buyLinkIsWeidianUrl_(url) {
  var parts = weidianOriginalUrlParts_(buyLinkUnwrapUrl_(url).url);
  return !!parts && /(^|\.)weidian\.com$/i.test(parts.host);
}

function buyLinkMatchTemplate_(url, templateUrl, depth) {
  if (depth > MAX_DECODE_PASSES) return null;
  var actual = weidianOriginalUrlParts_(buyLinkUnwrapUrl_(url).url);
  var expected = weidianOriginalUrlParts_(buyLinkUnwrapUrl_(templateUrl).url);
  if (!actual || !expected || actual.host !== expected.host
    || weidianOriginalNonWeidian_(actual) || weidianOriginalNonWeidian_(expected)) return null;
  var ids = weidianOriginalMatchPart_(actual.path, expected.path);
  if (!ids) return null;
  for (var i = 0; i < expected.params.length; i++) {
    var param = expected.params[i];
    var pattern = weidianOriginalDecode_(param.value);
    if (buyLinkIgnoreSourceParam_(param.name, pattern)) continue;
    var values = actual.params.filter(function(item) {
      return item.name.toLowerCase() === param.name.toLowerCase();
    });
    if (!values.length) return null;
    var nestedPattern = buyLinkUnwrapUrl_(param.value);
    for (var j = 0; j < values.length; j++) {
      var matched = /^https?:\/\//i.test(nestedPattern.url)
        ? buyLinkMatchTemplate_(values[j].value, nestedPattern.url, depth + 1)
        : weidianOriginalMatchPart_(weidianOriginalDecode_(values[j].value), pattern);
      if (!matched) return null;
      ids = ids.concat(matched);
    }
  }
  return ids.length ? ids : null;
}

function resolveBuySourceLink_(url, templates) {
  // 只检查待转换链接的外层地址；buy 参数中嵌套的微店地址仍可用于提取商品 ID。
  // 每次转换都判断，兼容已打开页面或旧批次携带的微店原始模板。
  if (buyLinkIsWeidianUrl_(url)) return { ok: false, matched: false, reason: '微店原始链接不参与 buy 互转，已保留。' };
  var matches = [];
  (Array.isArray(templates) ? templates : []).forEach(function(template) {
    if (!template || !template.templateUrl
      || (template.channelCode && String(template.channelCode).toUpperCase() !== 'WEIDIAN')
      || buyLinkIsWeidianUrl_(template.templateUrl)) return;
    // 只匹配外层来源模板；未知网站不能因内嵌微店链接而参与替换。
    var ids = buyLinkMatchTemplate_(url, template.templateUrl, 0);
    (ids || []).forEach(function(id) {
      matches.push({ productId: id, templateName: template.templateName || template.templateCode || '链接模板' });
    });
  });
  var ids = [];
  matches.forEach(function(match) { if (ids.indexOf(match.productId) < 0) ids.push(match.productId); });
  if (ids.length > 1) return { ok: false, matched: true, reason: '匹配到多个不同商品 ID，已保留原链接。' };
  if (!matches.length) return { ok: false, matched: false, reason: '链接未匹配现有链接模板。' };
  return { ok: true, matched: true, productId: ids[0], templateName: matches[0].templateName, reason: '' };
}

function buyLinkReplaceTokens_(value, productId, invitationCode) {
  return String(value).replace(/\{PRODUCT_ID\}|%7BPRODUCT_ID%7D/gi, productId)
    .replace(/\{INVITE_CODE\}|%7BINVITE_CODE%7D/gi, encodeURIComponent(invitationCode));
}

function buyLinkRenderUrl_(templateUrl, productId, invitationCode, depth) {
  if (depth > MAX_DECODE_PASSES) throw new Error('模板中的链接嵌套过深。');
  var unwrapped = buyLinkUnwrapUrl_(templateUrl);
  var url = unwrapped.url;
  if (!weidianOriginalUrlParts_(url)) throw new Error('链接模板必须是有效的 HTTP 或 HTTPS 网址。');
  var question = url.indexOf('?');
  var prefix = question < 0 ? url : url.substring(0, question);
  prefix = buyLinkReplaceTokens_(prefix, productId, invitationCode);
  if (question < 0) return prefix;
  var tail = url.substring(question + 1);
  var hash = tail.indexOf('#');
  var fragment = hash < 0 ? '' : tail.substring(hash);
  var query = hash < 0 ? tail : tail.substring(0, hash);
  var rendered = [];
  query.split('&').forEach(function(pair) {
    if (!pair) return;
    var equal = pair.indexOf('=');
    var rawName = equal < 0 ? pair : pair.substring(0, equal);
    var value = equal < 0 ? '' : pair.substring(equal + 1);
    var name = weidianOriginalDecode_(rawName);
    var nested = buyLinkUnwrapUrl_(value);
    if (/^https?:\/\//i.test(nested.url)) {
      var inner = buyLinkRenderUrl_(nested.url, productId, invitationCode, depth + 1);
      for (var n = 0; n < nested.depth; n++) inner = encodeURIComponent(inner);
      rendered.push(rawName + '=' + inner);
    } else if (buyLinkInvitationParam_(name) || /\{INVITE_CODE\}/i.test(weidianOriginalDecode_(value))) {
      if (invitationCode) rendered.push(rawName + '=' + encodeURIComponent(invitationCode));
    } else {
      // PRODUCT_ID 为纯数字，不会引入查询参数或路径分隔符。
      var replaced = value.replace(/\{PRODUCT_ID\}/gi, productId).replace(/%7BPRODUCT_ID%7D/gi, productId);
      rendered.push(rawName + (equal < 0 ? '' : '=' + replaced));
    }
  });
  return prefix + (rendered.length ? '?' + rendered.join('&') : '') + buyLinkReplaceTokens_(fragment, productId, invitationCode);
}

function buildBuyTargetLink_(template, productId, invitationCode) {
  if (!template || !template.templateUrl || !/^\d+$/.test(String(productId))) throw new Error('目标模板或商品 ID 无效。');
  if (template.channelCode && String(template.channelCode).toUpperCase() !== 'WEIDIAN') throw new Error('请选择微店商品对应的 buy 模板。');
  if (buyLinkIsWeidianUrl_(template.templateUrl)) throw new Error('微店原始链接不属于 buy 模板。');
  var decodedTemplate = weidianOriginalDecode_(template.templateUrl);
  if (!/\{PRODUCT_ID\}/i.test(decodedTemplate)) throw new Error('目标模板缺少商品 ID 占位符。');
  if (/\{[A-Z_]+\}/i.test(decodedTemplate.replace(/\{(?:PRODUCT_ID|INVITE_CODE)\}/gi, ''))) throw new Error('目标模板包含无法替换的占位符。');
  var code = String(invitationCode == null ? '' : invitationCode).trim();
  var result = buyLinkRenderUrl_(template.templateUrl, String(productId), code, 0);
  // 验证生成结果仍对应同一商品，避免错误模板批量写入。
  var resolved = resolveBuySourceLink_(result, [template]);
  if (!resolved.ok || resolved.productId !== String(productId)) throw new Error('目标模板无法生成可识别的商品链接，请检查模板配置。');
  return result;
}

function buyLinkValidateTarget_(templates, index, invitationCode) {
  if (!Array.isArray(templates) || !templates.length) throw new Error('请先加载现有链接模板。');
  if (index === '' || index === null || index === undefined || !Number.isInteger(Number(index)) || Number(index) < 0 || !templates[Number(index)]) {
    throw new Error('请选择目标 buy 平台。');
  }
  var target = templates[Number(index)];
  var parts = weidianOriginalUrlParts_(buyLinkUnwrapUrl_(target.templateUrl).url);
  if (!parts || /(^|\.)weidian\.com$/i.test(parts.host)) throw new Error('请选择目标 buy 平台。');
  return buildBuyTargetLink_(target, '7847984949', invitationCode);
}

function previewBuyTargetLink(payload) {
  payload = payload || {};
  try {
    return { ok: true, url: buyLinkValidateTarget_(payload.templates, payload.targetTemplateIndex, payload.invitationCode), reason: '' };
  } catch (err) {
    return { ok: false, reason: err.message };
  }
}

function buyLinkEmptyCounts_() {
  return { scannedCount: 0, matchedCount: 0, successCount: 0, skippedCount: 0, failedCount: 0, changedLinkCount: 0 };
}

function startBuyLinkConversion(payload) {
  payload = payload || {};
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  if (String(payload.spreadsheetId || '') !== ss.getId()) throw new Error('当前表格文件与页面信息不一致，请重新打开转换页面。');
  buyLinkValidateTarget_(payload.templates, payload.targetTemplateIndex, payload.invitationCode);
  // 点击开始时读取当前选区；之后的批次只使用此次快照，不随用户切换选区而改变。
  var selection = getBuyLinkSelection();
  var ranges = selection.blocks.map(function(block) {
    return Object.assign({}, block, buyLinkEmptyCounts_(), { done: false });
  });
  var run = {
    scope: 'selection', spreadsheetId: ss.getId(), templates: payload.templates, targetTemplateIndex: Number(payload.targetTemplateIndex),
    invitationCode: String(payload.invitationCode == null ? '' : payload.invitationCode).trim(),
    selection: selection, ranges: ranges, rangeIndex: 0, offset: 0, done: false, totals: buyLinkEmptyCounts_(),
    totalCells: selection.cellCount
  };
  buyLinkAdvanceRun_(run);
  return run;
}

function buyLinkAdvanceRun_(run) {
  while (run.rangeIndex < run.ranges.length) {
    var range = run.ranges[run.rangeIndex];
    if (!range.done && run.offset < range.rowCount * range.columnCount) break;
    range.done = true;
    run.rangeIndex++;
    run.offset = 0;
  }
  run.done = run.rangeIndex >= run.ranges.length;
}

function buyLinkConvertUrl_(url, run, cache) {
  if (Object.prototype.hasOwnProperty.call(cache, url)) return cache[url];
  var source = resolveBuySourceLink_(url, run.templates);
  var result;
  if (!source.ok) {
    result = { status: source.matched ? 'failed' : 'skipped', message: source.reason };
  } else {
    var target = buildBuyTargetLink_(run.templates[run.targetTemplateIndex], source.productId, run.invitationCode);
    result = target === url
      ? { status: 'skipped', message: '已是目标平台和邀请码。' }
      : { status: 'success', url: target, message: source.templateName };
  }
  cache[url] = result;
  return result;
}

function buyLinkUpdateCell_(cell, run, cache) {
  var display = cell.getDisplayValue();
  if (!/link/i.test(display)) return { status: 'skipped', message: '单元格文字已改变，不再包含 LINK。' };
  if (cell.isPartOfMerge()) return { status: 'skipped', message: '合并单元格已跳过。' };
  if (!cell.canEdit()) return { status: 'skipped', message: '单元格受保护，没有编辑权限。' };
  var formula = cell.getFormula();
  if (formula) {
    // 只改根 HYPERLINK 函数的常量 URL，保留标签表达式、分隔符和整个公式。
    var match = formula.match(/^(\s*=\s*HYPERLINK\s*\(\s*)"((?:""|[^"])*)"(\s*[,;)])/i);
    if (!match) return { status: 'skipped', message: '公式中的链接不是可直接替换的 HYPERLINK 常量网址，已保留公式。' };
    var formulaUrl = match[2].replace(/""/g, '"');
    var formulaResult = buyLinkConvertUrl_(formulaUrl, run, cache);
    if (formulaResult.status !== 'success') return formulaResult;
    var end = match[1].length + match[2].length + 2;
    cell.setFormula(match[1] + '"' + escapeFormulaString_(formulaResult.url) + '"' + formula.substring(end));
    return { status: 'success', changedLinkCount: 1, message: '已更新超链接，保留原公式和显示文字。' };
  }
  var rich = cell.getRichTextValue();
  if (!rich || !rich.getText()) return { status: 'skipped', message: '未找到单元格中的超链接。' };
  var spans = [];
  var text = rich.getText();
  // getRuns 按样式切分；逐字符取得实际链接，兼容同样式的多个不同超链接。
  for (var i = 0; i < text.length; i++) {
    var url = rich.getLinkUrl(i, i + 1);
    if (!url) continue;
    var previous = spans[spans.length - 1];
    if (previous && previous.url === url && previous.end === i) {
      previous.end = i + 1;
    } else {
      spans.push({ start: i, end: i + 1, url: url });
    }
  }
  if (!spans.length) return { status: 'skipped', message: '未找到单元格中的超链接。' };
  var builder = rich.copy();
  var changed = 0;
  var failed = 0;
  var messages = [];
  spans.forEach(function(span) {
    var converted = buyLinkConvertUrl_(span.url, run, cache);
    if (converted.status === 'success') {
      builder.setLinkUrl(span.start, span.end, converted.url);
      changed++;
    } else {
      if (converted.status === 'failed') failed++;
      if (messages.indexOf(converted.message) < 0) messages.push(converted.message);
    }
  });
  if (!changed) return { status: failed ? 'failed' : 'skipped', message: messages.join('；') };
  rich.getRuns().forEach(function(styleRun) {
    builder.setTextStyle(styleRun.getStartIndex(), styleRun.getEndIndex(), styleRun.getTextStyle());
  });
  cell.setRichTextValue(builder.build());
  return {
    status: 'success', changedLinkCount: changed,
    message: '已更新 ' + changed + ' 个超链接，保留文字和样式。' + (messages.length ? '其余链接：' + messages.join('；') : '')
  };
}

function processBuyLinkConversionBatch(payload) {
  var run = payload && payload.run;
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  // 拒绝旧的全表任务，防止已打开的旧页面继续扫描未选择的单元格。
  if (!run || run.scope !== 'selection' || run.spreadsheetId !== ss.getId() || !run.selection
    || run.selection.spreadsheetId !== ss.getId() || !Array.isArray(run.selection.blocks)
    || !Array.isArray(run.ranges) || !run.ranges.length || run.ranges.length !== run.selection.blocks.length
    || !Number.isInteger(run.rangeIndex) || run.rangeIndex < 0 || run.rangeIndex > run.ranges.length
    || !Number.isInteger(run.offset) || run.offset < 0) throw new Error('选区转换进度无效，请重新打开页面并选择区域。');
  var validRanges = run.ranges.every(function(range, index) {
    var block = run.selection.blocks[index];
    return block && ['row', 'column', 'rowCount', 'columnCount'].every(function(key) {
      return Number.isInteger(range[key]) && range[key] > 0 && range[key] === block[key];
    }) && range.row + range.rowCount - 1 <= run.selection.maxRows
      && range.column + range.columnCount - 1 <= run.selection.maxColumns;
  });
  if (!validRanges || (run.rangeIndex < run.ranges.length
    && run.offset > run.ranges[run.rangeIndex].rowCount * run.ranges[run.rangeIndex].columnCount)) {
    throw new Error('选区转换进度无效，请重新选择区域。');
  }
  buyLinkValidateTarget_(run.templates, run.targetTemplateIndex, run.invitationCode);
  var lock = LockService.getDocumentLock() || LockService.getScriptLock();
  if (!lock.tryLock(1000)) throw new Error('另一个链接转换正在运行，请稍后继续。');
  try {
    buyLinkAdvanceRun_(run);
    var items = [];
    if (run.done) return { run: run, items: items, message: '所选区域已处理完成。' };
    var progress = run.ranges[run.rangeIndex];
    var sheet = ss.getSheetById(run.selection.sheetId);
    function record(cell, outcome) {
      var countKey = outcome.status === 'success' ? 'successCount' : outcome.status === 'failed' ? 'failedCount' : 'skippedCount';
      progress[countKey]++;
      run.totals[countKey]++;
      var changed = outcome.changedLinkCount || 0;
      progress.changedLinkCount += changed;
      run.totals.changedLinkCount += changed;
      items.push({ sheetName: run.selection.sheetName, cell: cell, status: outcome.status, message: outcome.message });
    }
    if (!sheet || sheet.getMaxRows() !== run.selection.maxRows || sheet.getMaxColumns() !== run.selection.maxColumns) {
      var message = !sheet ? '选区所属工作表已被删除，请重新选择区域。' : '选区所属工作表的行列数量已改变，已停止转换，请重新选择区域。';
      record('', { status: 'failed', message: message });
      run.ranges.forEach(function(range) { range.done = true; });
      buyLinkAdvanceRun_(run);
      return { run: run, items: items, message: message };
    }
    var rowOffset = Math.floor(run.offset / progress.columnCount);
    var columnOffset = run.offset % progress.columnCount;
    var row = progress.row + rowOffset;
    var column = progress.column + columnOffset;
    // 每个批次都限制在一个去重后的选区块内，绝不跨入未选中的行列。
    var columns = Math.min(2000, progress.columnCount - columnOffset);
    var rows = columnOffset === 0 && columns === progress.columnCount
      ? Math.min(200, Math.max(1, Math.floor(2000 / columns)), progress.rowCount - rowOffset) : 1;
    var range = sheet.getRange(row, column, rows, columns);
    var values = range.getDisplayValues();
    var cache = Object.create(null);
    var candidates = 0;
    var deadline = Date.now() + 20000;
    var stop = false;
    for (var r = 0; r < rows && !stop; r++) {
      for (var c = 0; c < columns; c++) {
        if (candidates >= 50 || Date.now() >= deadline) { stop = true; break; }
        progress.scannedCount++;
        run.totals.scannedCount++;
        run.offset++;
        if (!/link/i.test(values[r][c])) continue;
        candidates++;
        progress.matchedCount++;
        run.totals.matchedCount++;
        var address = indexToColumn_(column + c) + (row + r);
        try {
          record(address, buyLinkUpdateCell_(sheet.getRange(row + r, column + c), run, cache));
        } catch (err) {
          record(address, { status: 'failed', message: err.message || String(err) });
        }
      }
    }
    SpreadsheetApp.flush();
    buyLinkAdvanceRun_(run);
    return {
      run: run, items: items,
      message: run.done ? '所选区域已处理完成。' : '已扫描选区内 ' + run.totals.scannedCount + ' 个格子，继续处理下一批。'
    };
  } finally {
    lock.releaseLock();
  }
}
