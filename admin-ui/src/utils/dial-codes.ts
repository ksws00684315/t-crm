/*
 * Country calling codes for the phone and WhatsApp fields: ISO 3166-1 alpha-2, ITU country
 * calling code (digits only, without the plus) and the English / Chinese region names from
 * ICU. Generated once from the public-domain datasets/country-codes table; edit by hand only
 * to add or correct a country.
 */

export interface DialCountry {
  /** ISO 3166-1 alpha-2. */
  iso: string;
  /** ITU country calling code, digits only, without the leading plus. */
  dial: string;
  /** English region name. */
  en: string;
  /** Chinese region name. */
  zh: string;
}

export const DIAL_COUNTRIES: readonly DialCountry[] = [
  { iso: "AF", dial: "93", en: "Afghanistan", zh: "阿富汗" },
  { iso: "AX", dial: "358", en: "Åland Islands", zh: "奥兰群岛" },
  { iso: "AL", dial: "355", en: "Albania", zh: "阿尔巴尼亚" },
  { iso: "DZ", dial: "213", en: "Algeria", zh: "阿尔及利亚" },
  { iso: "AS", dial: "1", en: "American Samoa", zh: "美属萨摩亚" },
  { iso: "AD", dial: "376", en: "Andorra", zh: "安道尔" },
  { iso: "AO", dial: "244", en: "Angola", zh: "安哥拉" },
  { iso: "AI", dial: "1", en: "Anguilla", zh: "安圭拉" },
  { iso: "AQ", dial: "672", en: "Antarctica", zh: "南极洲" },
  { iso: "AG", dial: "1", en: "Antigua & Barbuda", zh: "安提瓜和巴布达" },
  { iso: "AR", dial: "54", en: "Argentina", zh: "阿根廷" },
  { iso: "AM", dial: "374", en: "Armenia", zh: "亚美尼亚" },
  { iso: "AW", dial: "297", en: "Aruba", zh: "阿鲁巴" },
  { iso: "AU", dial: "61", en: "Australia", zh: "澳大利亚" },
  { iso: "AT", dial: "43", en: "Austria", zh: "奥地利" },
  { iso: "AZ", dial: "994", en: "Azerbaijan", zh: "阿塞拜疆" },
  { iso: "BS", dial: "1", en: "Bahamas", zh: "巴哈马" },
  { iso: "BH", dial: "973", en: "Bahrain", zh: "巴林" },
  { iso: "BD", dial: "880", en: "Bangladesh", zh: "孟加拉国" },
  { iso: "BB", dial: "1", en: "Barbados", zh: "巴巴多斯" },
  { iso: "BY", dial: "375", en: "Belarus", zh: "白俄罗斯" },
  { iso: "BE", dial: "32", en: "Belgium", zh: "比利时" },
  { iso: "BZ", dial: "501", en: "Belize", zh: "伯利兹" },
  { iso: "BJ", dial: "229", en: "Benin", zh: "贝宁" },
  { iso: "BM", dial: "1", en: "Bermuda", zh: "百慕大" },
  { iso: "BT", dial: "975", en: "Bhutan", zh: "不丹" },
  { iso: "BO", dial: "591", en: "Bolivia", zh: "玻利维亚" },
  { iso: "BA", dial: "387", en: "Bosnia & Herzegovina", zh: "波斯尼亚和黑塞哥维那" },
  { iso: "BW", dial: "267", en: "Botswana", zh: "博茨瓦纳" },
  { iso: "BV", dial: "47", en: "Bouvet Island", zh: "布韦岛" },
  { iso: "BR", dial: "55", en: "Brazil", zh: "巴西" },
  { iso: "IO", dial: "246", en: "British Indian Ocean Territory", zh: "英属印度洋领地" },
  { iso: "VG", dial: "1", en: "British Virgin Islands", zh: "英属维尔京群岛" },
  { iso: "BN", dial: "673", en: "Brunei", zh: "文莱" },
  { iso: "BG", dial: "359", en: "Bulgaria", zh: "保加利亚" },
  { iso: "BF", dial: "226", en: "Burkina Faso", zh: "布基纳法索" },
  { iso: "BI", dial: "257", en: "Burundi", zh: "布隆迪" },
  { iso: "KH", dial: "855", en: "Cambodia", zh: "柬埔寨" },
  { iso: "CM", dial: "237", en: "Cameroon", zh: "喀麦隆" },
  { iso: "CA", dial: "1", en: "Canada", zh: "加拿大" },
  { iso: "CV", dial: "238", en: "Cape Verde", zh: "佛得角" },
  { iso: "BQ", dial: "599", en: "Caribbean Netherlands", zh: "荷属加勒比区" },
  { iso: "KY", dial: "1", en: "Cayman Islands", zh: "开曼群岛" },
  { iso: "CF", dial: "236", en: "Central African Republic", zh: "中非共和国" },
  { iso: "TD", dial: "235", en: "Chad", zh: "乍得" },
  { iso: "CL", dial: "56", en: "Chile", zh: "智利" },
  { iso: "CN", dial: "86", en: "China", zh: "中国" },
  { iso: "CX", dial: "61", en: "Christmas Island", zh: "圣诞岛" },
  { iso: "CC", dial: "61", en: "Cocos (Keeling) Islands", zh: "科科斯（基林）群岛" },
  { iso: "CO", dial: "57", en: "Colombia", zh: "哥伦比亚" },
  { iso: "KM", dial: "269", en: "Comoros", zh: "科摩罗" },
  { iso: "CG", dial: "242", en: "Congo - Brazzaville", zh: "刚果（布）" },
  { iso: "CD", dial: "243", en: "Congo - Kinshasa", zh: "刚果（金）" },
  { iso: "CK", dial: "682", en: "Cook Islands", zh: "库克群岛" },
  { iso: "CR", dial: "506", en: "Costa Rica", zh: "哥斯达黎加" },
  { iso: "CI", dial: "225", en: "Côte d’Ivoire", zh: "科特迪瓦" },
  { iso: "HR", dial: "385", en: "Croatia", zh: "克罗地亚" },
  { iso: "CU", dial: "53", en: "Cuba", zh: "古巴" },
  { iso: "CW", dial: "599", en: "Curaçao", zh: "库拉索" },
  { iso: "CY", dial: "357", en: "Cyprus", zh: "塞浦路斯" },
  { iso: "CZ", dial: "420", en: "Czechia", zh: "捷克" },
  { iso: "DK", dial: "45", en: "Denmark", zh: "丹麦" },
  { iso: "DJ", dial: "253", en: "Djibouti", zh: "吉布提" },
  { iso: "DM", dial: "1", en: "Dominica", zh: "多米尼克" },
  { iso: "DO", dial: "1", en: "Dominican Republic", zh: "多米尼加共和国" },
  { iso: "EC", dial: "593", en: "Ecuador", zh: "厄瓜多尔" },
  { iso: "EG", dial: "20", en: "Egypt", zh: "埃及" },
  { iso: "SV", dial: "503", en: "El Salvador", zh: "萨尔瓦多" },
  { iso: "GQ", dial: "240", en: "Equatorial Guinea", zh: "赤道几内亚" },
  { iso: "ER", dial: "291", en: "Eritrea", zh: "厄立特里亚" },
  { iso: "EE", dial: "372", en: "Estonia", zh: "爱沙尼亚" },
  { iso: "SZ", dial: "268", en: "Eswatini", zh: "斯威士兰" },
  { iso: "ET", dial: "251", en: "Ethiopia", zh: "埃塞俄比亚" },
  { iso: "FK", dial: "500", en: "Falkland Islands", zh: "福克兰群岛" },
  { iso: "FO", dial: "298", en: "Faroe Islands", zh: "法罗群岛" },
  { iso: "FJ", dial: "679", en: "Fiji", zh: "斐济" },
  { iso: "FI", dial: "358", en: "Finland", zh: "芬兰" },
  { iso: "FR", dial: "33", en: "France", zh: "法国" },
  { iso: "GF", dial: "594", en: "French Guiana", zh: "法属圭亚那" },
  { iso: "PF", dial: "689", en: "French Polynesia", zh: "法属波利尼西亚" },
  { iso: "TF", dial: "262", en: "French Southern Territories", zh: "法属南部领地" },
  { iso: "GA", dial: "241", en: "Gabon", zh: "加蓬" },
  { iso: "GM", dial: "220", en: "Gambia", zh: "冈比亚" },
  { iso: "GE", dial: "995", en: "Georgia", zh: "格鲁吉亚" },
  { iso: "DE", dial: "49", en: "Germany", zh: "德国" },
  { iso: "GH", dial: "233", en: "Ghana", zh: "加纳" },
  { iso: "GI", dial: "350", en: "Gibraltar", zh: "直布罗陀" },
  { iso: "GR", dial: "30", en: "Greece", zh: "希腊" },
  { iso: "GL", dial: "299", en: "Greenland", zh: "格陵兰" },
  { iso: "GD", dial: "1", en: "Grenada", zh: "格林纳达" },
  { iso: "GP", dial: "590", en: "Guadeloupe", zh: "瓜德罗普" },
  { iso: "GU", dial: "1", en: "Guam", zh: "关岛" },
  { iso: "GT", dial: "502", en: "Guatemala", zh: "危地马拉" },
  { iso: "GG", dial: "44", en: "Guernsey", zh: "根西岛" },
  { iso: "GN", dial: "224", en: "Guinea", zh: "几内亚" },
  { iso: "GW", dial: "245", en: "Guinea-Bissau", zh: "几内亚比绍" },
  { iso: "GY", dial: "592", en: "Guyana", zh: "圭亚那" },
  { iso: "HT", dial: "509", en: "Haiti", zh: "海地" },
  { iso: "HM", dial: "672", en: "Heard & McDonald Islands", zh: "赫德岛和麦克唐纳群岛" },
  { iso: "HN", dial: "504", en: "Honduras", zh: "洪都拉斯" },
  { iso: "HK", dial: "852", en: "Hong Kong SAR China", zh: "中国香港特别行政区" },
  { iso: "HU", dial: "36", en: "Hungary", zh: "匈牙利" },
  { iso: "IS", dial: "354", en: "Iceland", zh: "冰岛" },
  { iso: "IN", dial: "91", en: "India", zh: "印度" },
  { iso: "ID", dial: "62", en: "Indonesia", zh: "印度尼西亚" },
  { iso: "IR", dial: "98", en: "Iran", zh: "伊朗" },
  { iso: "IQ", dial: "964", en: "Iraq", zh: "伊拉克" },
  { iso: "IE", dial: "353", en: "Ireland", zh: "爱尔兰" },
  { iso: "IM", dial: "44", en: "Isle of Man", zh: "马恩岛" },
  { iso: "IL", dial: "972", en: "Israel", zh: "以色列" },
  { iso: "IT", dial: "39", en: "Italy", zh: "意大利" },
  { iso: "JM", dial: "1", en: "Jamaica", zh: "牙买加" },
  { iso: "JP", dial: "81", en: "Japan", zh: "日本" },
  { iso: "JE", dial: "44", en: "Jersey", zh: "泽西岛" },
  { iso: "JO", dial: "962", en: "Jordan", zh: "约旦" },
  { iso: "KZ", dial: "7", en: "Kazakhstan", zh: "哈萨克斯坦" },
  { iso: "KE", dial: "254", en: "Kenya", zh: "肯尼亚" },
  { iso: "KI", dial: "686", en: "Kiribati", zh: "基里巴斯" },
  { iso: "KW", dial: "965", en: "Kuwait", zh: "科威特" },
  { iso: "KG", dial: "996", en: "Kyrgyzstan", zh: "吉尔吉斯斯坦" },
  { iso: "LA", dial: "856", en: "Laos", zh: "老挝" },
  { iso: "LV", dial: "371", en: "Latvia", zh: "拉脱维亚" },
  { iso: "LB", dial: "961", en: "Lebanon", zh: "黎巴嫩" },
  { iso: "LS", dial: "266", en: "Lesotho", zh: "莱索托" },
  { iso: "LR", dial: "231", en: "Liberia", zh: "利比里亚" },
  { iso: "LY", dial: "218", en: "Libya", zh: "利比亚" },
  { iso: "LI", dial: "423", en: "Liechtenstein", zh: "列支敦士登" },
  { iso: "LT", dial: "370", en: "Lithuania", zh: "立陶宛" },
  { iso: "LU", dial: "352", en: "Luxembourg", zh: "卢森堡" },
  { iso: "MO", dial: "853", en: "Macao SAR China", zh: "中国澳门特别行政区" },
  { iso: "MG", dial: "261", en: "Madagascar", zh: "马达加斯加" },
  { iso: "MW", dial: "265", en: "Malawi", zh: "马拉维" },
  { iso: "MY", dial: "60", en: "Malaysia", zh: "马来西亚" },
  { iso: "MV", dial: "960", en: "Maldives", zh: "马尔代夫" },
  { iso: "ML", dial: "223", en: "Mali", zh: "马里" },
  { iso: "MT", dial: "356", en: "Malta", zh: "马耳他" },
  { iso: "MH", dial: "692", en: "Marshall Islands", zh: "马绍尔群岛" },
  { iso: "MQ", dial: "596", en: "Martinique", zh: "马提尼克" },
  { iso: "MR", dial: "222", en: "Mauritania", zh: "毛里塔尼亚" },
  { iso: "MU", dial: "230", en: "Mauritius", zh: "毛里求斯" },
  { iso: "YT", dial: "262", en: "Mayotte", zh: "马约特" },
  { iso: "MX", dial: "52", en: "Mexico", zh: "墨西哥" },
  { iso: "FM", dial: "691", en: "Micronesia", zh: "密克罗尼西亚" },
  { iso: "MD", dial: "373", en: "Moldova", zh: "摩尔多瓦" },
  { iso: "MC", dial: "377", en: "Monaco", zh: "摩纳哥" },
  { iso: "MN", dial: "976", en: "Mongolia", zh: "蒙古" },
  { iso: "ME", dial: "382", en: "Montenegro", zh: "黑山" },
  { iso: "MS", dial: "1", en: "Montserrat", zh: "蒙特塞拉特" },
  { iso: "MA", dial: "212", en: "Morocco", zh: "摩洛哥" },
  { iso: "MZ", dial: "258", en: "Mozambique", zh: "莫桑比克" },
  { iso: "MM", dial: "95", en: "Myanmar (Burma)", zh: "缅甸" },
  { iso: "NA", dial: "264", en: "Namibia", zh: "纳米比亚" },
  { iso: "NR", dial: "674", en: "Nauru", zh: "瑙鲁" },
  { iso: "NP", dial: "977", en: "Nepal", zh: "尼泊尔" },
  { iso: "NL", dial: "31", en: "Netherlands", zh: "荷兰" },
  { iso: "NC", dial: "687", en: "New Caledonia", zh: "新喀里多尼亚" },
  { iso: "NZ", dial: "64", en: "New Zealand", zh: "新西兰" },
  { iso: "NI", dial: "505", en: "Nicaragua", zh: "尼加拉瓜" },
  { iso: "NE", dial: "227", en: "Niger", zh: "尼日尔" },
  { iso: "NG", dial: "234", en: "Nigeria", zh: "尼日利亚" },
  { iso: "NU", dial: "683", en: "Niue", zh: "纽埃" },
  { iso: "NF", dial: "672", en: "Norfolk Island", zh: "诺福克岛" },
  { iso: "KP", dial: "850", en: "North Korea", zh: "朝鲜" },
  { iso: "MK", dial: "389", en: "North Macedonia", zh: "北马其顿" },
  { iso: "MP", dial: "1", en: "Northern Mariana Islands", zh: "北马里亚纳群岛" },
  { iso: "NO", dial: "47", en: "Norway", zh: "挪威" },
  { iso: "OM", dial: "968", en: "Oman", zh: "阿曼" },
  { iso: "PK", dial: "92", en: "Pakistan", zh: "巴基斯坦" },
  { iso: "PW", dial: "680", en: "Palau", zh: "帕劳" },
  { iso: "PS", dial: "970", en: "Palestinian Territories", zh: "巴勒斯坦领土" },
  { iso: "PA", dial: "507", en: "Panama", zh: "巴拿马" },
  { iso: "PG", dial: "675", en: "Papua New Guinea", zh: "巴布亚新几内亚" },
  { iso: "PY", dial: "595", en: "Paraguay", zh: "巴拉圭" },
  { iso: "PE", dial: "51", en: "Peru", zh: "秘鲁" },
  { iso: "PH", dial: "63", en: "Philippines", zh: "菲律宾" },
  { iso: "PN", dial: "870", en: "Pitcairn Islands", zh: "皮特凯恩群岛" },
  { iso: "PL", dial: "48", en: "Poland", zh: "波兰" },
  { iso: "PT", dial: "351", en: "Portugal", zh: "葡萄牙" },
  { iso: "PR", dial: "1", en: "Puerto Rico", zh: "波多黎各" },
  { iso: "QA", dial: "974", en: "Qatar", zh: "卡塔尔" },
  { iso: "RE", dial: "262", en: "Réunion", zh: "留尼汪" },
  { iso: "RO", dial: "40", en: "Romania", zh: "罗马尼亚" },
  { iso: "RU", dial: "7", en: "Russia", zh: "俄罗斯" },
  { iso: "RW", dial: "250", en: "Rwanda", zh: "卢旺达" },
  { iso: "WS", dial: "685", en: "Samoa", zh: "萨摩亚" },
  { iso: "SM", dial: "378", en: "San Marino", zh: "圣马力诺" },
  { iso: "ST", dial: "239", en: "São Tomé & Príncipe", zh: "圣多美和普林西比" },
  { iso: "SA", dial: "966", en: "Saudi Arabia", zh: "沙特阿拉伯" },
  { iso: "SN", dial: "221", en: "Senegal", zh: "塞内加尔" },
  { iso: "RS", dial: "381", en: "Serbia", zh: "塞尔维亚" },
  { iso: "SC", dial: "248", en: "Seychelles", zh: "塞舌尔" },
  { iso: "SL", dial: "232", en: "Sierra Leone", zh: "塞拉利昂" },
  { iso: "SG", dial: "65", en: "Singapore", zh: "新加坡" },
  { iso: "SX", dial: "1", en: "Sint Maarten", zh: "荷属圣马丁" },
  { iso: "SK", dial: "421", en: "Slovakia", zh: "斯洛伐克" },
  { iso: "SI", dial: "386", en: "Slovenia", zh: "斯洛文尼亚" },
  { iso: "SB", dial: "677", en: "Solomon Islands", zh: "所罗门群岛" },
  { iso: "SO", dial: "252", en: "Somalia", zh: "索马里" },
  { iso: "ZA", dial: "27", en: "South Africa", zh: "南非" },
  {
    iso: "GS",
    dial: "500",
    en: "South Georgia & South Sandwich Islands",
    zh: "南乔治亚和南桑威奇群岛"
  },
  { iso: "KR", dial: "82", en: "South Korea", zh: "韩国" },
  { iso: "SS", dial: "211", en: "South Sudan", zh: "南苏丹" },
  { iso: "ES", dial: "34", en: "Spain", zh: "西班牙" },
  { iso: "LK", dial: "94", en: "Sri Lanka", zh: "斯里兰卡" },
  { iso: "BL", dial: "590", en: "St. Barthélemy", zh: "圣巴泰勒米" },
  { iso: "SH", dial: "290", en: "St. Helena", zh: "圣赫勒拿" },
  { iso: "KN", dial: "1", en: "St. Kitts & Nevis", zh: "圣基茨和尼维斯" },
  { iso: "LC", dial: "1", en: "St. Lucia", zh: "圣卢西亚" },
  { iso: "MF", dial: "590", en: "St. Martin", zh: "法属圣马丁" },
  { iso: "PM", dial: "508", en: "St. Pierre & Miquelon", zh: "圣皮埃尔和密克隆群岛" },
  { iso: "VC", dial: "1", en: "St. Vincent & Grenadines", zh: "圣文森特和格林纳丁斯" },
  { iso: "SD", dial: "249", en: "Sudan", zh: "苏丹" },
  { iso: "SR", dial: "597", en: "Suriname", zh: "苏里南" },
  { iso: "SJ", dial: "47", en: "Svalbard & Jan Mayen", zh: "斯瓦尔巴和扬马延" },
  { iso: "SE", dial: "46", en: "Sweden", zh: "瑞典" },
  { iso: "CH", dial: "41", en: "Switzerland", zh: "瑞士" },
  { iso: "SY", dial: "963", en: "Syria", zh: "叙利亚" },
  { iso: "TW", dial: "886", en: "Taiwan", zh: "台湾" },
  { iso: "TJ", dial: "992", en: "Tajikistan", zh: "塔吉克斯坦" },
  { iso: "TZ", dial: "255", en: "Tanzania", zh: "坦桑尼亚" },
  { iso: "TH", dial: "66", en: "Thailand", zh: "泰国" },
  { iso: "TL", dial: "670", en: "Timor-Leste", zh: "东帝汶" },
  { iso: "TG", dial: "228", en: "Togo", zh: "多哥" },
  { iso: "TK", dial: "690", en: "Tokelau", zh: "托克劳" },
  { iso: "TO", dial: "676", en: "Tonga", zh: "汤加" },
  { iso: "TT", dial: "1", en: "Trinidad & Tobago", zh: "特立尼达和多巴哥" },
  { iso: "TN", dial: "216", en: "Tunisia", zh: "突尼斯" },
  { iso: "TR", dial: "90", en: "Türkiye", zh: "土耳其" },
  { iso: "TM", dial: "993", en: "Turkmenistan", zh: "土库曼斯坦" },
  { iso: "TC", dial: "1", en: "Turks & Caicos Islands", zh: "特克斯和凯科斯群岛" },
  { iso: "TV", dial: "688", en: "Tuvalu", zh: "图瓦卢" },
  { iso: "VI", dial: "1", en: "U.S. Virgin Islands", zh: "美属维尔京群岛" },
  { iso: "UG", dial: "256", en: "Uganda", zh: "乌干达" },
  { iso: "UA", dial: "380", en: "Ukraine", zh: "乌克兰" },
  { iso: "AE", dial: "971", en: "United Arab Emirates", zh: "阿拉伯联合酋长国" },
  { iso: "GB", dial: "44", en: "United Kingdom", zh: "英国" },
  { iso: "US", dial: "1", en: "United States", zh: "美国" },
  { iso: "UY", dial: "598", en: "Uruguay", zh: "乌拉圭" },
  { iso: "UZ", dial: "998", en: "Uzbekistan", zh: "乌兹别克斯坦" },
  { iso: "VU", dial: "678", en: "Vanuatu", zh: "瓦努阿图" },
  { iso: "VA", dial: "39", en: "Vatican City", zh: "梵蒂冈" },
  { iso: "VE", dial: "58", en: "Venezuela", zh: "委内瑞拉" },
  { iso: "VN", dial: "84", en: "Vietnam", zh: "越南" },
  { iso: "WF", dial: "681", en: "Wallis & Futuna", zh: "瓦利斯和富图纳" },
  { iso: "EH", dial: "212", en: "Western Sahara", zh: "西撒哈拉" },
  { iso: "YE", dial: "967", en: "Yemen", zh: "也门" },
  { iso: "ZM", dial: "260", en: "Zambia", zh: "赞比亚" },
  { iso: "ZW", dial: "263", en: "Zimbabwe", zh: "津巴布韦" }
];

/**
 * Entries that share a dial code keep one canonical name when a pasted number is split, so
 * "+1…" resolves to the United States instead of Canada and "+7…" to Russia instead of
 * Kazakhstan. The country field is a label only; the stored number is the same either way.
 */
const PREFERRED_ISO: Readonly<Record<string, string>> = {
  "1": "US",
  "7": "RU",
  "39": "IT",
  "44": "GB",
  "47": "NO",
  "61": "AU",
  "64": "NZ",
  "212": "MA",
  "262": "RE",
  "290": "SH",
  "358": "FI",
  "500": "FK",
  "590": "GP",
  "599": "CW",
  "672": "NF"
};

const BY_ISO = new Map(DIAL_COUNTRIES.map(country => [country.iso, country]));

export function countryName(country: DialCountry, locale: string): string {
  return locale.toLowerCase().startsWith("zh") ? country.zh : country.en;
}

/** Options for the selector, ordered by the name shown in the current language. */
export function countriesByName(locale: string): readonly DialCountry[] {
  return [...DIAL_COUNTRIES].sort((a, b) =>
    countryName(a, locale).localeCompare(countryName(b, locale), locale)
  );
}

/** Selector search: matches the shown name or the leading digits of a dial code ("+49", "49"). */
export function filterCountries(query: string, locale: string): readonly DialCountry[] {
  const text = query.trim().toLowerCase();
  if (!text) return countriesByName(locale);
  const digits = text.replace(/^\+/, "");
  return countriesByName(locale).filter(
    country =>
      countryName(country, locale).toLowerCase().includes(text) ||
      (digits.length > 0 && country.dial.startsWith(digits))
  );
}

export function findCountry(iso: string): DialCountry | undefined {
  return BY_ISO.get(iso);
}

/** Region subtag of a browser language tag, e.g. "de-DE" → "DE"; empty when unknown. */
export function defaultIso(locale: string): string {
  let region = "";
  try {
    region = new Intl.Locale(locale).region ?? "";
  } catch {
    region = "";
  }
  return region && BY_ISO.has(region) ? region : "";
}

/**
 * Splits a pasted international number into country and national digits by the longest dial
 * code prefix. Returns null when the value carries no digits or no known prefix.
 */
export function splitE164(value: string): { iso: string; national: string } | null {
  if (!value.trim().startsWith("+")) return null;
  const digits = value.replace(/\D/g, "");
  if (!digits) return null;
  let match: DialCountry | undefined;
  for (const country of DIAL_COUNTRIES) {
    if (!digits.startsWith(country.dial)) continue;
    if (!match || country.dial.length > match.dial.length) {
      match = country;
    } else if (
      country.dial.length === match.dial.length &&
      PREFERRED_ISO[country.dial] === country.iso
    ) {
      match = country;
    }
  }
  if (!match) return null;
  return { iso: match.iso, national: digits.slice(match.dial.length) };
}

/** Stored value: E.164 with the national digits only, or the empty string when there is no number. */
export function composeE164(iso: string, national: string): string {
  const country = BY_ISO.get(iso);
  const digits = national.replace(/\D/g, "");
  return country && digits ? `+${country.dial}${digits}` : "";
}
