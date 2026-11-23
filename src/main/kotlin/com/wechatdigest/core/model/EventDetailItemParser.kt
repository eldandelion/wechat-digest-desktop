package com.wechatdigest.core.model

import java.util.Locale

/**
 * Resolved UI-ready representation of an [EventSection], separating structured metadata cards
 * from remaining narrative prose.
 */
data class ResolvedSectionDetails(
    val items: List<EventDetailItem>,
    val remainingContent: String
)

/**
 * Sanitizes typed [EventDetailItem] entries and converts legacy bullet lists (`- Key: Value`,
 * plain `- ...` sentence bullets, and [EventSection.bullets]) into titled icon cards cleanly
 * without requiring re-extraction.
 */
object EventDetailItemParser {

    const val MAX_ITEMS_PER_SECTION = 16
    const val MAX_ITEM_LABEL_CHARS = 48
    const val MAX_ITEM_VALUE_CHARS = 180
    const val MAX_SECTION_HEADING_CHARS = 160
    const val MAX_SECTION_CONTENT_CHARS = 4_000
    const val MAX_CARD_TITLE_CHARS = 240
    const val MAX_CARD_SUMMARY_CHARS = 4_000
    private const val MAX_LEGACY_CONTENT_LINES = 100
    internal const val MAX_LEGACY_LINE_CHARS = 512

    /** Canonical Material icon slugs shared cleanly between [core] matching and Compose [EventDetailIconCatalog]. */
    val ALL_ICON_SLUGS: List<String> = listOf(
        // Dates, Schedules & Deadlines (12)
        "calendar_today", "event", "date_range", "event_available",
        "schedule", "access_time", "alarm", "timer",
        "hourglass_bottom", "event_busy", "update", "history",
        // Locations, Campuses, Rooms & Travel (11)
        "location_on", "meeting_room", "apartment", "school",
        "map", "directions", "directions_bus", "flight",
        "hotel", "public", "video_call",
        // Documents, Forms, Copies & Formats (12)
        "description", "article", "assignment", "folder",
        "attach_file", "badge", "content_copy", "print",
        "picture_as_pdf", "photo_camera", "qr_code", "draw",
        // Eligibility, Audience & Rules (14)
        "verified_user", "how_to_reg", "check_circle", "checklist",
        "fact_check", "workspace_premium", "groups", "diversity_3",
        "gavel", "rule", "policy", "lock",
        "security", "balance",
        // Exams, Courses & Academic Milestones (9)
        "quiz", "menu_book", "edit_note", "grade",
        "psychology", "science", "biotech", "calculate",
        "translate",
        // Contacts & People (8)
        "contact_mail", "email", "phone", "forum",
        "person", "supervisor_account", "support_agent", "campaign",
        // Links, Portals & Actions (10)
        "link", "open_in_new", "language", "cloud_upload",
        "download", "touch_app", "send", "assignment_turned_in",
        "drive_file_rename_outline", "archive",
        // Warnings, Health, Food, Payments & Logistics (20)
        "warning", "report_problem", "priority_high", "block",
        "payments", "account_balance", "receipt_long", "credit_card",
        "laptop", "devices", "wifi", "mic",
        "medical_services", "restaurant", "inventory_2", "card_giftcard",
        "info", "lightbulb", "bookmark", "star"
    )

    private val ALL_ICON_SLUG_SET: Set<String> = ALL_ICON_SLUGS.toSet()

    private val ICON_ALIAS_MAP: Map<String, String> = mapOf(
        "calendar" to "calendar_today",
        "date" to "calendar_today",
        "clock" to "schedule",
        "time" to "schedule",
        "deadline" to "hourglass_bottom",
        "pin" to "location_on",
        "place" to "location_on",
        "location" to "location_on",
        "room" to "meeting_room",
        "building" to "apartment",
        "campus" to "school",
        "graduation" to "school",
        "bus" to "directions_bus",
        "shuttle" to "directions_bus",
        "plane" to "flight",
        "bed" to "hotel",
        "dorm" to "hotel",
        "globe" to "public",
        "video" to "video_call",
        "zoom" to "video_call",
        "file" to "description",
        "doc" to "description",
        "document" to "description",
        "form" to "assignment",
        "copy" to "content_copy",
        "copies" to "content_copy",
        "pdf" to "picture_as_pdf",
        "photo" to "photo_camera",
        "camera" to "photo_camera",
        "qr" to "qr_code",
        "sign" to "draw",
        "signature" to "draw",
        "id" to "badge",
        "id_card" to "badge",
        "passport" to "badge",
        "check" to "check_circle",
        "register" to "how_to_reg",
        "award" to "workspace_premium",
        "scholarship" to "workspace_premium",
        "group" to "groups",
        "audience" to "groups",
        "book" to "menu_book",
        "exam" to "quiz",
        "mail" to "email",
        "call" to "phone",
        "user" to "person",
        "advisor" to "supervisor_account",
        "support" to "support_agent",
        "chat" to "forum",
        "upload" to "cloud_upload",
        "website" to "language",
        "portal" to "open_in_new",
        "alert" to "warning",
        "warning_amber" to "warning",
        "ban" to "block",
        "prohibited" to "block",
        "money" to "payments",
        "fee" to "payments",
        "payment" to "payments",
        "bank" to "account_balance",
        "receipt" to "receipt_long",
        "invoice" to "receipt_long",
        "card" to "credit_card",
        "computer" to "laptop",
        "pc" to "laptop",
        "device" to "devices",
        "medical" to "medical_services",
        "health" to "medical_services",
        "hospital" to "medical_services",
        "food" to "restaurant",
        "meal" to "restaurant",
        "dining" to "restaurant",
        "gift" to "card_giftcard",
        "tip" to "lightbulb"
    )

    private val leadingBulletRegex = Regex("""^\s*(?:[*•▪◦]\s*|-(?![\d-])\s*|\d{1,2}[.)]\s+)""")
    private val inlineNumberedSplitRegex = Regex("""(?:\(\d{1,2}\)|（\d{1,2}）|(?<=\s)\d{1,2}\))\s*""")
    private val inlineBulletSymbolSplitRegex = Regex("""[•▪◦]+|\s+·\s+""")
    private val cjkCharRegex = Regex("""[\u4E00-\u9FFF]""")
    private val abbreviationEndings = setOf(
        "dr.", "prof.", "mr.", "ms.", "mrs.", "no.", "vol.", "fig.",
        "e.g.", "i.e.", "vs.", "etc.", "st.", "u.s.", "n.a."
    )
    private val standaloneUrlRegex = Regex("""^(?:https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)
    private val emailRegex = Regex("""(?<![A-Za-z0-9._%+\-])[A-Za-z0-9._%+\-]{1,64}@(?:[A-Za-z0-9\-]{1,63}\.)+[A-Za-z]{2,24}""")
    private val urlInTextRegex = Regex("""https?://\S+|www\.\S+|mailto:\S+""", RegexOption.IGNORE_CASE)
    private val whitespaceRegex = Regex("""\s+""")
    private val controlAndBidiRegex = Regex("""[\u0000-\u001F\u007F\u0080-\u009F\u061C\u200B-\u200F\u2028-\u202E\u2060\u2066-\u2069\uFEFF]+""")
    private val multiLineControlAndBidiRegex = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u0080-\u009F\u061C\u200B-\u200F\u2028-\u202E\u2060\u2066-\u2069\uFEFF]+""")

    private val placeholderValues = setOf(
        "n/a",
        "n.a",
        "na",
        "none",
        "null",
        "nil",
        "tbd",
        "tba",
        "pending",
        "unknown",
        "to be determined",
        "to be announced",
        "not specified",
        "not applicable",
        "not mentioned",
        "not stated",
        "not provided",
        "not available",
        "unspecified",
        "no data",
        "-",
        "--",
        "---",
        "—",
        "...",
        "…",
        "无",
        "暂无",
        "待定",
        "未定",
        "待通知",
        "不适用",
        "未说明",
        "未提及",
        "未提供",
        "未公布"
    )

    private data class KindRule(
        val kind: EventDetailKind,
        val rawKeywords: List<String>,
        val asciiWordPatterns: List<Regex>,
        val nonAsciiKeywords: List<String>
    )

    private fun buildKindRule(kind: EventDetailKind, keywords: List<String>): KindRule {
        val asciiPatterns = keywords
            .filter { kw -> kw.all { it.code < 128 } }
            .map { kw -> Regex("""(?<![a-z0-9])${Regex.escape(kw)}(?![a-z0-9])""") }
        val nonAscii = keywords.filter { kw -> kw.any { it.code >= 128 } }
        return KindRule(kind, keywords, asciiPatterns, nonAscii)
    }

    private val kindRules: List<KindRule> = listOf(
        buildKindRule(
            EventDetailKind.COPIES,
            listOf("copies", "bound copies", "printed copies", "hard copies", "hard copy", "printed copy", "bound copy", "print", "纸质版", "纸质", "复印件", "份数")
        ),
        buildKindRule(
            EventDetailKind.FORMAT,
            listOf("format", "file format", "naming", "file naming", "subject format", "email subject", "template", "pdf only", "格式", "命名", "模板")
        ),
        buildKindRule(
            EventDetailKind.ELIGIBILITY,
            listOf("eligibility", "credits", "coursework", "prerequisite", "prerequisites", "qualification", "gpa", "cleared", "学分", "资格", "条件")
        ),
        buildKindRule(
            EventDetailKind.PAYMENT,
            listOf("fee", "fees", "payment", "stipend", "scholarship", "award", "reward", "reimbursement", "cost", "price", "rmb", "yuan", "费用", "奖金", "补贴", "经费", "报名费")
        ),
        buildKindRule(
            EventDetailKind.DEVICE,
            listOf("laptop", "calculator", "student id", "id card", "equipment", "software", "device", "badge", "证件", "学生证", "一卡通", "计算器", "电脑")
        ),
        buildKindRule(
            EventDetailKind.DOCUMENT,
            listOf("document", "documents", "attachment", "form", "application form", "clearance form", "diploma cover", "certificate", "packet", "proposal report", "表格", "附件", "材料单", "手续单", "封皮", "报告", "证书")
        ),
        buildKindRule(
            EventDetailKind.PERSON,
            listOf("advisor", "supervisor", "speaker", "instructor", "professor", "reviewer", "signature", "signed", "导师", "签字", "签名", "主讲人", "负责人")
        ),
        buildKindRule(
            EventDetailKind.AUDIENCE,
            listOf("audience", "participants", "cohort", "master students", "doctoral students", "graduate students", "undergraduates", "target students", "全体", "硕士生", "博士生", "研究生", "本科生", "参会人员")
        ),
        buildKindRule(
            EventDetailKind.DEADLINE,
            listOf("deadline", "due date", "due by", "due", "cutoff", "submission deadline", "submission cutoff", "closes", "截止", "截止时间", "截止日期", "逾期")
        ),
        buildKindRule(EventDetailKind.DATE, listOf("date", "day", "dates", "exam date", "event date", "日期")),
        buildKindRule(EventDetailKind.TIME, listOf("time", "hours", "duration", "window", "schedule", "时间", "时段")),
        buildKindRule(
            EventDetailKind.LOCATION,
            listOf("location", "venue", "room", "place", "address", "building", "hall", "classroom", "office", "auditorium", "地点", "教室", "会议室", "考场", "地址", "行政楼", "教务处")
        ),
        buildKindRule(EventDetailKind.CONTACT, listOf("contact", "email", "e-mail", "phone", "tel", "organizer", "ta", "联系", "联系人", "邮箱", "电话")),
        buildKindRule(EventDetailKind.LINK, listOf("link", "url", "website", "portal", "registration link", "canvas", "链接", "网址", "平台")),
        buildKindRule(EventDetailKind.EXAM, listOf("exam", "quiz", "assessment", "closed-book", "open-book", "grading", "考试", "考核", "闭卷", "开卷")),
        buildKindRule(EventDetailKind.RULE, listOf("rule", "rules", "policy", "regulation", "penalty", "mandatory", "honor code", "规定", "规则", "纪律")),
        buildKindRule(EventDetailKind.WARNING, listOf("warning", "caution", "alert", "late submissions", "strictly prohibited", "注意", "警告", "严禁")),
        buildKindRule(EventDetailKind.ACTION, listOf("action", "step", "submit", "upload", "register", "collect", "pickup", "pick up", "visit", "apply", "提交", "上传", "领取", "报名")),
        buildKindRule(EventDetailKind.REQUIREMENT, listOf("requirement", "requirements", "materials", "bring", "note", "notes", "要求", "材料", "备注"))
    )

    private data class IconRule(
        val iconSlug: String,
        val rawKeywords: List<String>,
        val asciiWordPatterns: List<Regex>,
        val nonAsciiKeywords: List<String>
    )

    private fun buildIconRule(iconSlug: String, keywords: List<String>): IconRule {
        val asciiPatterns = keywords
            .filter { kw -> kw.all { it.code < 128 } }
            .map { kw -> Regex("""(?<![a-z0-9])${Regex.escape(kw)}(?![a-z0-9])""") }
        val nonAscii = keywords.filter { kw -> kw.any { it.code >= 128 } }
        return IconRule(iconSlug, keywords, asciiPatterns, nonAscii)
    }

    private val iconRules: List<IconRule> = listOf(
        buildIconRule("video_call", listOf("zoom", "tencent meeting", "voov", "teams", "webex", "google meet", "online meeting", "video call", "virtual room", "腾讯会议", "线上会议", "视频会议", "线上答辩")),
        buildIconRule("qr_code", listOf("qr code", "qr", "scan code", "mini program", "mini-program", "二维码", "扫码", "小程序")),
        buildIconRule("photo_camera", listOf("id photo", "passport photo", "photo", "photograph", "headshot", "portrait", "picture", "image", "照片", "证件照", "头像", "合影")),
        buildIconRule("picture_as_pdf", listOf("pdf", "adobe", "acrobat")),
        buildIconRule("draw", listOf("signature", "sign here", "signed", "handwritten", "autograph", "seal", "stamp", "签字", "签名", "盖章", "手写")),
        buildIconRule("badge", listOf("student id", "id card", "campus card", "passport", "work id", "pass card", "badge", "学生证", "身份证", "护照", "一卡通", "校园卡", "工牌", "通行证")),
        buildIconRule("print", listOf("print", "printed", "hard copy", "hardcopy", "hard copies", "printer", "color print", "打印", "纸质", "彩印")),
        buildIconRule("content_copy", listOf("copies", "copy", "photocopy", "duplicate", "triplicate", "份数", "复印件", "一式")),
        buildIconRule("archive", listOf("zip", "rar", "7z", "archive", "bundle", "compressed", "打包", "压缩包")),
        buildIconRule("cloud_upload", listOf("upload", "portal upload", "online submit", "submit online", "上传", "网申", "系统提交")),
        buildIconRule("download", listOf("download", "template", "sample form", "blank form", "下载", "模板", "样表")),
        buildIconRule("article", listOf("transcript", "cv", "resume", "essay", "proposal", "thesis", "report", "abstract", "statement", "成绩单", "简历", "开题", "论文", "报告", "摘要")),
        buildIconRule("assignment", listOf("application form", "registration form", "form", "dossier", "package", "packet", "申请表", "登记表", "申报表", "表格")),
        buildIconRule("folder", listOf("folder", "portfolio", "binder", "materials folder", "文件夹", "材料袋", "档案袋")),
        buildIconRule("attach_file", listOf("attachment", "enclosure", "appendix", "supporting", "附件", "附带")),
        buildIconRule("directions_bus", listOf("bus", "shuttle", "subway", "metro", "transit", "commute", "ferry", "coach", "班车", "大巴", "地铁", "公交", "校车")),
        buildIconRule("flight", listOf("flight", "airport", "airline", "boarding", "plane", "ticket", "航班", "机场", "机票", "乘机")),
        buildIconRule("hotel", listOf("hotel", "dorm", "dormitory", "accommodation", "lodging", "housing", "hostel", "住宿", "宿舍", "酒店", "宾馆")),
        buildIconRule("meeting_room", listOf("classroom", "conference room", "auditorium", "hall", "room", "lab", "studio", "office", "教室", "会议室", "礼堂", "报告厅", "实验室", "办公室", "考场")),
        buildIconRule("apartment", listOf("building", "tower", "block", "complex", "floor", "center", "centre", "行政楼", "教学楼", "楼", "馆", "中心")),
        buildIconRule("school", listOf("campus", "university", "college", "department", "faculty", "graduation", "commencement", "semester", "学位", "毕业", "校园", "学院", "校区", "教务")),
        buildIconRule("map", listOf("map", "route", "gate", "entrance", "wayfinding", "exit", "地图", "路线", "入口", "校门")),
        buildIconRule("directions", listOf("arrive", "arrival", "departure", "walk", "enter", "报到", "抵达", "入场")),
        buildIconRule("public", listOf("overseas", "international", "abroad", "global", "remote", "海外", "国际", "全球")),
        buildIconRule("workspace_premium", listOf("scholarship", "fellowship", "award", "prize", "honor", "honour", "merit", "distinction", "grant", "奖学金", "表彰", "荣誉", "奖项", "优秀")),
        buildIconRule("receipt_long", listOf("invoice", "receipt", "fapiao", "reimbursement", "reimburse", "voucher", "billing", "发票", "收据", "报销", "凭条")),
        buildIconRule("account_balance", listOf("bank", "wire", "transfer", "bursar", "finance", "treasury", "iban", "account number", "银行", "转账", "汇款", "财务", "账户")),
        buildIconRule("credit_card", listOf("card payment", "wechat pay", "alipay", "credit card", "debit", "top-up", "swipe", "刷卡", "微信支付", "支付宝", "充值")),
        buildIconRule("payments", listOf("fee", "tuition", "payment", "cost", "price", "stipend", "allowance", "budget", "rmb", "yuan", "usd", "学费", "费用", "缴费", "补贴", "补助", "经费")),
        buildIconRule("medical_services", listOf("medical", "health", "clinic", "hospital", "vaccine", "insurance", "physical exam", "doctor", "体检", "医疗", "医院", "医保", "保险", "疫苗")),
        buildIconRule("restaurant", listOf("meal", "lunch", "dinner", "breakfast", "food", "dining", "catering", "refreshment", "tea break", "banquet", "午餐", "晚餐", "早餐", "用餐", "餐饮", "茶歇", "盒饭")),
        buildIconRule("laptop", listOf("laptop", "computer", "pc", "mac", "notebook", "software", "python", "matlab", "电脑", "笔记本", "计算机", "软件")),
        buildIconRule("devices", listOf("device", "tablet", "ipad", "headset", "usb", "flash drive", "charger", "equipment", "设备", "平板", "u盘", "耳机")),
        buildIconRule("wifi", listOf("wifi", "wi-fi", "network", "vpn", "internet", "eduroam", "ssid", "wireless", "网络", "无线", "校园网")),
        buildIconRule("mic", listOf("mic", "microphone", "presentation", "speech", "oral", "talk", "pitch", "keynote", "slides", "ppt", "演讲", "汇报", "宣讲", "幻灯片")),
        buildIconRule("calculate", listOf("calculator", "formula", "math", "statistics", "calculation", "计算器", "公式", "数学", "统计")),
        buildIconRule("translate", listOf("language", "english", "chinese", "ielts", "toefl", "bilingual", "translation", "英文", "中文", "语言", "翻译", "英语", "四六级")),
        buildIconRule("quiz", listOf("exam", "quiz", "midterm", "final", "test", "closed-book", "open-book", "exam paper", "考试", "测验", "期中", "期末", "闭卷", "开卷")),
        buildIconRule("psychology", listOf("interview", "defense", "oral exam", "viva", "panel", "committee", "面试", "答辩", "评审")),
        buildIconRule("grade", listOf("gpa", "grade", "score", "credit", "credits", "passing", "rank", "percentile", "绩点", "成绩", "学分", "分数", "排名")),
        buildIconRule("menu_book", listOf("textbook", "reading", "syllabus", "handbook", "manual", "guidebook", "chapter", "教材", "书目", "手册", "大纲", "指南")),
        buildIconRule("science", listOf("lab", "experiment", "research", "project", "demo", "prototype", "实验", "科研", "研究")),
        buildIconRule("biotech", listOf("bio", "specimen", "sample", "swab", "blood test", "试剂", "化验", "生物", "样本")),
        buildIconRule("email", listOf("email", "e-mail", "mail", "inbox", "邮箱", "邮件", "电子邮箱")),
        buildIconRule("phone", listOf("phone", "tel", "telephone", "hotline", "call", "ext", "mobile", "电话", "热线", "手机", "座机")),
        buildIconRule("forum", listOf("wechat group", "group chat", "qq group", "forum", "slack", "discord", "微信群", "qq群", "答疑群", "交流群", "论坛")),
        buildIconRule("supervisor_account", listOf("advisor", "supervisor", "mentor", "dean", "chair", "director", "导师", "指导教师", "院长", "主任")),
        buildIconRule("support_agent", listOf("helpdesk", "it support", "support", "coordinator", "assistant", "counselor", "ta", "助教", "辅导员", "教务员", "客服", "咨询")),
        buildIconRule("campaign", listOf("announce", "spokesperson", "organizer", "host", "主讲", "主办", "发起")),
        buildIconRule("person", listOf("professor", "prof", "lecturer", "instructor", "presenter", "老师", "教授", "联系人")),
        buildIconRule("groups", listOf("all students", "cohort", "graduating", "master", "doctoral", "phd", "undergraduate", "freshmen", "faculty", "全体", "研究生", "硕士", "博士", "本科生", "新生")),
        buildIconRule("diversity_3", listOf("international students", "exchange", "club", "team", "partner", "留学生", "交换生", "团队", "社团")),
        buildIconRule("hourglass_bottom", listOf("deadline", "due", "cutoff", "expires", "submission closes", "截止", "最晚", "到期", "期限")),
        buildIconRule("event_busy", listOf("no late", "overdue", "late submissions", "closed", "expired", "cancelled", "canceled", "逾期", "过期", "不予受理", "取消")),
        buildIconRule("alarm", listOf("remind", "prompt", "early", "bell", "reminder", "提前", "准时", "提醒", "闹钟")),
        buildIconRule("timer", listOf("duration", "minutes", "countdown", "timed", "时长", "分钟", "限时", "倒计时")),
        buildIconRule("date_range", listOf("range", "period", "window", "week", "span", "期间", "时段")),
        buildIconRule("event_available", listOf("opens", "available", "bookable", "slot", "confirmed", "开放", "可预约", "生效")),
        buildIconRule("update", listOf("reschedule", "postponed", "updated", "recurring", "weekly", "改期", "延期", "更新", "每周")),
        buildIconRule("history", listOf("make-up", "makeup", "retake", "previous", "past", "补考", "补交", "往期")),
        buildIconRule("schedule", listOf("time", "am", "pm", "o'clock", "schedule", "时间", "上午", "下午", "晚上")),
        buildIconRule("calendar_today", listOf("date", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "日期", "星期")),
        buildIconRule("how_to_reg", listOf("register", "registration", "sign up", "signup", "rsvp", "enroll", "enrollment", "check-in", "报名", "注册", "选课", "签到", "预约")),
        buildIconRule("assignment_turned_in", listOf("turn in", "hand in", "submission", "submit", "complete", "提交", "递交", "交回", "完成")),
        buildIconRule("send", listOf("send", "mail to", "forward", "dispatch", "发送", "寄送", "投递")),
        buildIconRule("drive_file_rename_outline", listOf("rename", "naming", "subject line", "fill out", "fill in", "naming convention", "命名", "填写", "邮件主题", "格式")),
        buildIconRule("open_in_new", listOf("portal", "platform", "web page", "website", "system", "canvas", "blackboard", "平台", "系统", "网页", "教务系统")),
        buildIconRule("link", listOf("http", "https", "url", "link", "链接", "网址")),
        buildIconRule("block", listOf("prohibit", "prohibited", "forbidden", "not allowed", "ban", "banned", "must not", "strictly", "严禁", "禁止", "不得", "不许", "不允许")),
        buildIconRule("report_problem", listOf("penalty", "consequence", "void", "invalid", "disqualified", "revoke", "作废", "取消资格", "后果", "处分")),
        buildIconRule("priority_high", listOf("urgent", "immediately", "mandatory", "critical", "must", "务必", "必须", "紧急", "重要")),
        buildIconRule("warning", listOf("warning", "caution", "important", "notice", "attention", "注意", "警示", "提醒")),
        buildIconRule("gavel", listOf("policy", "regulation", "rule", "honor code", "disciplinary", "bylaw", "规定", "条例", "纪律", "准则")),
        buildIconRule("rule", listOf("allowed", "criteria", "standard", "condition", "compliance", "标准", "条件", "规范")),
        buildIconRule("lock", listOf("confidential", "password", "encrypted", "restricted", "private", "保密", "密码", "加密", "权限")),
        buildIconRule("security", listOf("safety", "clearance", "background check", "security", "安全", "安检", "保卫")),
        buildIconRule("fact_check", listOf("verify", "verification", "audit", "confirm", "review", "approve", "审核", "核验", "确认", "审批", "复核")),
        buildIconRule("checklist", listOf("checklist", "items", "materials", "prepare", "bring", "清单", "准备", "携带")),
        buildIconRule("verified_user", listOf("eligible", "eligibility", "qualified", "prerequisite", "qualification", "资格", "符合条件", "前提")),
        buildIconRule("inventory_2", listOf("supply", "supplies", "stationery", "kit", "gear", "box", "文具", "物品", "物资", "道具")),
        buildIconRule("card_giftcard", listOf("gift", "souvenir", "voucher", "coupon", "bonus", "perk", "welfare", "礼品", "纪念品", "优惠券", "福利")),
        buildIconRule("lightbulb", listOf("tip", "tips", "suggest", "suggestion", "recommend", "advice", "建议", "提示", "贴士")),
        buildIconRule("star", listOf("highlight", "featured", "priority", "special", "key", "亮点", "特色", "重点")),
        buildIconRule("bookmark", listOf("reference", "note", "bookmark", "memo", "参考", "备注"))
    )

    private val kindIconPools: Map<EventDetailKind, List<String>> = mapOf(
        EventDetailKind.DATE to listOf("calendar_today", "event", "date_range", "event_available", "update", "history", "schedule", "bookmark"),
        EventDetailKind.TIME to listOf("schedule", "access_time", "timer", "alarm", "update", "event_available", "date_range", "calendar_today"),
        EventDetailKind.DEADLINE to listOf("hourglass_bottom", "event_busy", "alarm", "timer", "priority_high", "schedule", "calendar_today", "warning"),
        EventDetailKind.LOCATION to listOf("location_on", "meeting_room", "apartment", "school", "map", "directions", "directions_bus", "hotel", "video_call", "public"),
        EventDetailKind.DOCUMENT to listOf("description", "article", "assignment", "folder", "attach_file", "badge", "picture_as_pdf", "archive", "bookmark", "fact_check"),
        EventDetailKind.COPIES to listOf("print", "content_copy", "description", "article", "assignment", "inventory_2", "folder", "attach_file"),
        EventDetailKind.FORMAT to listOf("drive_file_rename_outline", "picture_as_pdf", "photo_camera", "qr_code", "draw", "edit_note", "article", "rule"),
        EventDetailKind.ELIGIBILITY to listOf("verified_user", "workspace_premium", "grade", "fact_check", "check_circle", "school", "rule", "how_to_reg"),
        EventDetailKind.EXAM to listOf("quiz", "menu_book", "edit_note", "psychology", "grade", "calculate", "science", "translate", "biotech"),
        EventDetailKind.CONTACT to listOf("email", "contact_mail", "phone", "forum", "support_agent", "person", "supervisor_account", "campaign"),
        EventDetailKind.PERSON to listOf("person", "supervisor_account", "support_agent", "campaign", "contact_mail", "groups", "psychology", "verified_user"),
        EventDetailKind.AUDIENCE to listOf("groups", "diversity_3", "school", "person", "supervisor_account", "verified_user", "how_to_reg", "campaign"),
        EventDetailKind.LINK to listOf("link", "open_in_new", "language", "public", "qr_code", "cloud_upload", "download", "touch_app"),
        EventDetailKind.ACTION to listOf("check_circle", "how_to_reg", "assignment_turned_in", "cloud_upload", "download", "send", "touch_app", "drive_file_rename_outline"),
        EventDetailKind.REQUIREMENT to listOf("checklist", "fact_check", "verified_user", "inventory_2", "assignment", "check_circle", "rule", "info"),
        EventDetailKind.RULE to listOf("gavel", "rule", "policy", "balance", "security", "lock", "fact_check", "block"),
        EventDetailKind.WARNING to listOf("warning", "report_problem", "priority_high", "block", "event_busy", "alarm", "gavel", "info"),
        EventDetailKind.PAYMENT to listOf("payments", "account_balance", "receipt_long", "credit_card", "workspace_premium", "card_giftcard", "calculate", "check_circle"),
        EventDetailKind.DEVICE to listOf("laptop", "devices", "wifi", "mic", "qr_code", "photo_camera", "badge", "calculate"),
        EventDetailKind.OTHER to listOf("info", "lightbulb", "bookmark", "star", "checklist", "fact_check", "medical_services", "restaurant", "card_giftcard", "inventory_2")
    )

    /** Returns `true` when [raw] is blank or a vague placeholder string such as `"N/A"` or `"TBD"`. */
    fun isPlaceholderValue(raw: String): Boolean {
        val normalized = stripControlChars(raw)
            .trim()
            .trim('[', ']', '(', ')', '（', '）', '【', '】')
            .lowercase(Locale.ROOT)
            .trimEnd('.', '。', '!', '！')
            .trim()
        return normalized.isBlank() || normalized in placeholderValues
    }

    /** Returns a clean fallback label for [kind] when an item label is omitted or blank. */
    fun defaultLabel(kind: EventDetailKind): String = when (kind) {
        EventDetailKind.DATE -> "Date"
        EventDetailKind.TIME -> "Time"
        EventDetailKind.DEADLINE -> "Deadline"
        EventDetailKind.LOCATION -> "Location"
        EventDetailKind.DOCUMENT -> "Document"
        EventDetailKind.COPIES -> "Paper Copies"
        EventDetailKind.FORMAT -> "Format"
        EventDetailKind.ELIGIBILITY -> "Eligibility"
        EventDetailKind.EXAM -> "Exam Rule"
        EventDetailKind.CONTACT -> "Contact"
        EventDetailKind.PERSON -> "Person / Advisor"
        EventDetailKind.AUDIENCE -> "Participants"
        EventDetailKind.LINK -> "Link"
        EventDetailKind.ACTION -> "Action"
        EventDetailKind.REQUIREMENT -> "Requirements"
        EventDetailKind.RULE -> "Policy"
        EventDetailKind.WARNING -> "Important"
        EventDetailKind.PAYMENT -> "Fee / Award"
        EventDetailKind.DEVICE -> "Equipment / ID"
        EventDetailKind.OTHER -> "Detail"
    }

    /** Returns the canonical Material icon slug for [raw], or `null` when unrecognized. */
    fun normalizeIconSlug(raw: String): String? {
        val cleaned = raw.trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
        if (cleaned.isEmpty()) return null
        if (cleaned in ALL_ICON_SLUG_SET) return cleaned
        return ICON_ALIAS_MAP[cleaned]
    }

    /** Trims fields, strips control/Bidi chars, removes placeholders, bounds lengths, deduplicates, and assigns distinct icons to [rawItems]. */
    fun sanitizeItems(rawItems: List<EventDetailItem>): List<EventDetailItem> {
        val seen = mutableSetOf<Triple<EventDetailKind, String, String>>()
        val cleaned = mutableListOf<EventDetailItem>()
        for (raw in rawItems.take(MAX_ITEMS_PER_SECTION * 4)) {
            if (cleaned.size >= MAX_ITEMS_PER_SECTION) break
            val value = sanitizeSingleLine(raw.value, MAX_ITEM_VALUE_CHARS)
            if (isPlaceholderValue(value)) continue
            val candidateLabel = sanitizeSingleLine(raw.label, MAX_ITEM_LABEL_CHARS)
            val label = if (candidateLabel.isBlank() || isPlaceholderValue(candidateLabel)) {
                defaultLabel(raw.kind)
            } else {
                candidateLabel
            }
            val key = Triple(raw.kind, label.lowercase(Locale.ROOT), value.lowercase(Locale.ROOT))
            if (seen.add(key)) {
                cleaned.add(
                    EventDetailItem(
                        kind = raw.kind,
                        label = label,
                        value = value,
                        icon = normalizeIconSlug(raw.icon).orEmpty()
                    )
                )
            }
        }
        return assignDistinctIcons(cleaned)
    }

    /**
     * Assigns every card inside the same section a semantically matched, non-repeating Material icon
     * slug across [ALL_ICON_SLUGS] so adjacent cards do not display monotonous icons.
     */
    fun assignDistinctIcons(items: List<EventDetailItem>): List<EventDetailItem> {
        if (items.isEmpty()) return emptyList()
        val usedSlugs = mutableSetOf<String>()
        return items.map { item ->
            val candidates = rankCandidateIcons(item)
            val chosen = candidates.firstOrNull { usedSlugs.add(it) } ?: candidates.first()
            if (item.icon == chosen) item else item.copy(icon = chosen)
        }
    }

    private fun rankCandidateIcons(item: EventDetailItem): List<String> {
        val ordered = LinkedHashSet<String>()
        normalizeIconSlug(item.icon)?.let { ordered.add(it) }

        val labelLower = item.label.lowercase(Locale.ROOT).trim()
        val combinedLower = "${item.label} ${item.value}".lowercase(Locale.ROOT).trim()

        for (rule in iconRules) {
            if (matchesIconRule(rule, labelLower)) ordered.add(rule.iconSlug)
        }
        for (rule in iconRules) {
            if (matchesIconRule(rule, combinedLower)) ordered.add(rule.iconSlug)
        }
        kindIconPools[item.kind]?.forEach { ordered.add(it) }
        ALL_ICON_SLUGS.forEach { ordered.add(it) }
        return ordered.toList()
    }

    private fun matchesIconRule(rule: IconRule, normalizedText: String): Boolean {
        if (normalizedText.isBlank()) return false
        return rule.asciiWordPatterns.any { it.containsMatchIn(normalizedText) } ||
            rule.nonAsciiKeywords.any { normalizedText.contains(it) }
    }

    /**
     * Extracts individual atomic bullet statements from [section] for Pass 4 enrichment, splitting
     * previously merged single-item card values or legacy bullets into separate entries.
     */
    fun extractAtomicBulletsForEnrichment(section: EventSection): List<String> {
        val explicitBullets = sanitizeBullets(section.bullets)
        val contentLines = extractStructuredLinesFromContent(section.content)
        val rawCombined = (explicitBullets + contentLines).distinctBy { it.lowercase(Locale.ROOT) }
        val expandedFromBullets = rawCombined
            .flatMap { splitMergedValueIntoClauses(it) }
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (expandedFromBullets.size >= 2 || (expandedFromBullets.size == 1 && section.items.isEmpty())) {
            return expandedFromBullets.take(MAX_ITEMS_PER_SECTION)
        }

        val existingItems = sanitizeItems(section.items)
        if (existingItems.isNotEmpty()) {
            val expandedFromItems = existingItems
                .flatMap { item ->
                    val clauses = splitMergedValueIntoClauses(item.value)
                    if (clauses.size >= 2) clauses else listOf("${item.label}: ${item.value}")
                }
                .distinctBy { it.lowercase(Locale.ROOT) }
            if (expandedFromItems.size > existingItems.size) {
                return expandedFromItems.take(MAX_ITEMS_PER_SECTION)
            }
        }
        return expandedFromBullets.take(MAX_ITEMS_PER_SECTION)
    }

    private fun extractStructuredLinesFromContent(rawContent: String): List<String> {
        val content = sanitizeMultiLine(rawContent, MAX_SECTION_CONTENT_CHARS)
        if (content.isBlank()) return emptyList()
        return content.lineSequence()
            .take(MAX_LEGACY_CONTENT_LINES)
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    (leadingBulletRegex.containsMatchIn(line) || classifyKeyValueLine(line) is KeyValueResult.Parsed)
            }
            .map { line -> sanitizeSingleLine(line, MAX_ITEM_VALUE_CHARS).replaceFirst(leadingBulletRegex, "").trim() }
            .filter { !isPlaceholderValue(it) }
            .toList()
    }

    internal fun splitMergedValueIntoClauses(raw: String): List<String> {
        val bounded = sanitizeMultiLine(raw, MAX_SECTION_CONTENT_CHARS)
        if (bounded.isBlank()) return emptyList()

        val initial = bounded
            .split('\n')
            .flatMap { line -> line.split(inlineNumberedSplitRegex) }
            .flatMap { segment -> segment.split(inlineBulletSymbolSplitRegex) }
            .flatMap { segment -> splitSemicolonsSafely(segment) }
            .flatMap { segment -> splitLongClausesBySentence(segment) }
            .map { piece -> sanitizeSingleLine(piece, MAX_ITEM_VALUE_CHARS).replaceFirst(leadingBulletRegex, "").trim() }
            .filter { piece -> piece.length >= 4 && !isPlaceholderValue(piece) }
            .distinctBy { it.lowercase(Locale.ROOT) }

        if (initial.size >= 2) return initial.take(MAX_ITEMS_PER_SECTION)
        val fallback = sanitizeSingleLine(bounded, MAX_ITEM_VALUE_CHARS).replaceFirst(leadingBulletRegex, "").trim()
        return if (isPlaceholderValue(fallback)) emptyList() else listOf(fallback)
    }

    private fun splitSemicolonsSafely(text: String): List<String> {
        if (!text.contains(';') && !text.contains('；')) return listOf(text)
        val parts = mutableListOf<String>()
        var start = 0
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '；' || (ch == ';' && !looksLikeHtmlEntityEnd(text, i))) {
                parts.add(text.substring(start, i))
                start = i + 1
            }
        }
        if (start <= text.length) parts.add(text.substring(start))
        val clean = parts.map { it.trim() }.filter { it.length >= 4 }
        return if (clean.size >= 2) clean else listOf(text)
    }

    private fun looksLikeHtmlEntityEnd(text: String, semicolonIdx: Int): Boolean {
        val lookbackStart = (semicolonIdx - 8).coerceAtLeast(0)
        val slice = text.substring(lookbackStart, semicolonIdx)
        val ampIdx = slice.lastIndexOf('&')
        if (ampIdx < 0) return false
        val body = slice.substring(ampIdx + 1)
        return body.isNotEmpty() && body.all { it.isLetterOrDigit() || it == '#' }
    }

    private fun splitLongClausesBySentence(text: String): List<String> {
        val trimmed = text.trim()
        val hasEnoughLength = trimmed.length >= 36 || cjkCharRegex.findAll(trimmed).take(22).count() >= 22
        if (!hasEnoughLength) return listOf(trimmed)

        val parts = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < trimmed.length) {
            val ch = trimmed[i]
            val isCjkBreak = ch == '。' || ch == '！' || ch == '？'
            val isAsciiBreak = (ch == '.' || ch == '!' || ch == '?') &&
                (i + 1 < trimmed.length && trimmed[i + 1].isWhitespace())
            if (isCjkBreak || isAsciiBreak) {
                val leftChunk = trimmed.substring(start, i + 1).trim()
                val rightStart = skipWhitespace(trimmed, i + 1)
                val rightChunk = if (rightStart < trimmed.length) trimmed.substring(rightStart).trim() else ""
                if (shouldBreakSentence(leftChunk, rightChunk, isCjkBreak)) {
                    parts.add(leftChunk)
                    start = rightStart
                    i = rightStart
                    continue
                }
            }
            i++
        }
        if (start < trimmed.length) {
            parts.add(trimmed.substring(start).trim())
        }
        return parts.filter { it.isNotBlank() }.ifEmpty { listOf(trimmed) }
    }

    private fun skipWhitespace(text: String, fromIndex: Int): Int {
        var idx = fromIndex
        while (idx < text.length && text[idx].isWhitespace()) idx++
        return idx
    }

    private fun shouldBreakSentence(leftChunk: String, rightChunk: String, isCjkBreak: Boolean): Boolean {
        val minPartChars = if (isCjkBreak) 8 else 12
        if (leftChunk.length < minPartChars || rightChunk.length < minPartChars) return false
        val lowerLeft = leftChunk.lowercase(Locale.ROOT)
        if (abbreviationEndings.any { lowerLeft.endsWith(it) }) return false
        if (!isCjkBreak && leftChunk.length >= 2 && leftChunk[leftChunk.lastIndex] == '.' &&
            leftChunk[leftChunk.lastIndex - 1].isLetter() &&
            (leftChunk.length == 2 || leftChunk[leftChunk.lastIndex - 2].isWhitespace())
        ) {
            return false
        }
        return true
    }

    /** Sanitizes [rawBullets] by stripping leading bullet symbols, controls, and placeholders in order. */
    fun sanitizeBullets(rawBullets: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val cleaned = mutableListOf<String>()
        for (raw in rawBullets.take(MAX_ITEMS_PER_SECTION * 4)) {
            if (cleaned.size >= MAX_ITEMS_PER_SECTION) break
            val normalized = sanitizeSingleLine(raw, MAX_ITEM_VALUE_CHARS)
                .replaceFirst(leadingBulletRegex, "")
                .trim()
            if (isPlaceholderValue(normalized)) continue
            if (seen.add(normalized.lowercase(Locale.ROOT))) {
                cleaned.add(normalized)
            }
        }
        return cleaned
    }

    /**
     * Returns `true` when [section] contains unstructured bullets or raw metadata lines that have not
     * yet been converted into LLM-generated structured [EventSection.items] (eligible for Pass 4 enrichment).
     */
    fun hasUnstructuredBullets(section: EventSection): Boolean {
        if (sanitizeItems(section.items).isNotEmpty()) return false
        if (sanitizeBullets(section.bullets).isNotEmpty()) return true
        val content = sanitizeMultiLine(section.content, MAX_SECTION_CONTENT_CHARS)
        return content.lineSequence().any { rawLine ->
            val trimmed = rawLine.trim()
            trimmed.isNotEmpty() &&
                (leadingBulletRegex.containsMatchIn(trimmed) || classifyKeyValueLine(trimmed) is KeyValueResult.Parsed)
        }
    }

    /**
     * Resolves [section] for display: prefers structured [EventSection.items] when non-empty; otherwise
     * converts [EventSection.bullets], legacy `- Key: Value` lines, and plain `- ...` sentence bullets
     * into titled icon cards while leaving plain non-bullet prose in [ResolvedSectionDetails.remainingContent].
     */
    fun resolveSectionDisplay(section: EventSection): ResolvedSectionDetails {
        val sanitizedExplicit = sanitizeItems(section.items)
        if (sanitizedExplicit.isNotEmpty()) {
            return ResolvedSectionDetails(
                items = sanitizedExplicit,
                remainingContent = sanitizeMultiLine(section.content, MAX_SECTION_CONTENT_CHARS)
            )
        }

        val extractedItems = mutableListOf<EventDetailItem>()
        for (bullet in sanitizeBullets(section.bullets)) {
            if (extractedItems.size >= MAX_ITEMS_PER_SECTION) break
            val parsed = parseBulletOrKeyValue(bullet, forcePlainCard = true)
            if (parsed is LineResolution.Card) {
                extractedItems.add(parsed.item)
            }
        }

        val rawContent = sanitizeMultiLine(section.content, MAX_SECTION_CONTENT_CHARS)
        if (rawContent.isBlank()) {
            return ResolvedSectionDetails(items = sanitizeItems(extractedItems), remainingContent = "")
        }

        val remainingLines = mutableListOf<String>()
        var lineCount = 0

        for (rawLine in rawContent.lineSequence()) {
            val trimmedLine = rawLine.trim()
            if (trimmedLine.isEmpty()) {
                if (remainingLines.isNotEmpty() && remainingLines.last().isNotEmpty()) {
                    remainingLines.add("")
                }
                continue
            }
            lineCount++
            val resolution = if (lineCount <= MAX_LEGACY_CONTENT_LINES && extractedItems.size < MAX_ITEMS_PER_SECTION) {
                parseContentLine(trimmedLine)
            } else {
                LineResolution.Prose(trimmedLine)
            }
            when (resolution) {
                is LineResolution.Card -> extractedItems.add(resolution.item)
                is LineResolution.Prose -> remainingLines.add(resolution.line)
                LineResolution.SkipPlaceholder -> Unit
            }
        }

        val remainingText = remainingLines.joinToString("\n").trim()
        return ResolvedSectionDetails(
            items = sanitizeItems(extractedItems),
            remainingContent = remainingText
        )
    }

    private sealed interface LineResolution {
        data class Card(val item: EventDetailItem) : LineResolution
        data class Prose(val line: String) : LineResolution
        data object SkipPlaceholder : LineResolution
    }

    private sealed interface KeyValueResult {
        data class Parsed(val item: EventDetailItem) : KeyValueResult
        data object Placeholder : KeyValueResult
        data object NotKeyValue : KeyValueResult
    }

    private fun parseContentLine(trimmedLine: String): LineResolution {
        if (trimmedLine.length > MAX_LEGACY_LINE_CHARS) return LineResolution.Prose(trimmedLine)
        val isBulletLine = leadingBulletRegex.containsMatchIn(trimmedLine)
        return parseBulletOrKeyValue(trimmedLine, forcePlainCard = isBulletLine)
    }

    private fun parseBulletOrKeyValue(trimmedLine: String, forcePlainCard: Boolean): LineResolution {
        return when (val kv = classifyKeyValueLine(trimmedLine)) {
            is KeyValueResult.Parsed -> LineResolution.Card(kv.item)
            KeyValueResult.Placeholder -> LineResolution.SkipPlaceholder
            KeyValueResult.NotKeyValue -> {
                if (!forcePlainCard) {
                    LineResolution.Prose(trimmedLine)
                } else {
                    val candidate = stripControlChars(trimmedLine).replaceFirst(leadingBulletRegex, "").trim()
                    when {
                        isPlaceholderValue(candidate) -> LineResolution.SkipPlaceholder
                        candidate.length > MAX_ITEM_VALUE_CHARS -> LineResolution.Prose(trimmedLine)
                        else -> {
                            val kind = inferKind(candidate, candidate, allowValueKeywordFallback = false)
                            LineResolution.Card(EventDetailItem(kind = kind, label = defaultLabel(kind), value = candidate))
                        }
                    }
                }
            }
        }
    }

    private fun classifyKeyValueLine(trimmedLine: String): KeyValueResult {
        if (trimmedLine.length > MAX_LEGACY_LINE_CHARS) return KeyValueResult.NotKeyValue
        val candidate = stripControlChars(trimmedLine).replaceFirst(leadingBulletRegex, "").trim()
        if (candidate.isBlank()) return KeyValueResult.NotKeyValue

        if (standaloneUrlRegex.matches(candidate)) {
            return if (candidate.length > MAX_ITEM_VALUE_CHARS) {
                KeyValueResult.NotKeyValue
            } else {
                KeyValueResult.Parsed(EventDetailItem(kind = EventDetailKind.LINK, label = defaultLabel(EventDetailKind.LINK), value = candidate))
            }
        }

        val urlSpans = urlInTextRegex.findAll(candidate).map { it.range }.toList()
        val colonIdx = candidate.indices.firstOrNull { idx ->
            val ch = candidate[idx]
            (ch == ':' || ch == '：') &&
                !urlSpans.any { idx in it } &&
                !(idx > 0 && candidate[idx - 1].isDigit() && candidate.getOrNull(idx + 1)?.isDigit() == true)
        } ?: return KeyValueResult.NotKeyValue
        if (colonIdx !in 1..36) return KeyValueResult.NotKeyValue

        val rawKey = candidate.substring(0, colonIdx).trim()
        val rawValue = candidate.substring(colonIdx + 1).trim()
        if (!isValidKeyCandidate(rawKey)) return KeyValueResult.NotKeyValue
        if (isPlaceholderValue(rawValue)) return KeyValueResult.Placeholder
        if (rawValue.length > MAX_ITEM_VALUE_CHARS) return KeyValueResult.NotKeyValue

        val inferredKind = inferKind(rawKey, rawValue, allowValueKeywordFallback = true)
        val wordCount = rawKey.split(whitespaceRegex).size
        val looksLikeMetadataKey = inferredKind != EventDetailKind.OTHER ||
            (wordCount <= 3 && rawKey.length <= 24 && !rawKey.contains(',') && !rawKey.contains('，') && rawValue.length <= 140)
        if (!looksLikeMetadataKey) return KeyValueResult.NotKeyValue

        return KeyValueResult.Parsed(EventDetailItem(kind = inferredKind, label = rawKey, value = rawValue))
    }

    private fun isValidKeyCandidate(rawKey: String): Boolean {
        val lower = rawKey.lowercase(Locale.ROOT)
        if (lower.startsWith("http") || lower.startsWith("ftp") || lower.startsWith("mailto")) return false
        if (rawKey.contains('/') || rawKey.contains("://")) return false
        if (!rawKey.any { it.isLetter() }) return false
        return true
    }

    private fun inferKind(rawKey: String, rawValue: String, allowValueKeywordFallback: Boolean): EventDetailKind {
        val normalizedKey = rawKey.lowercase(Locale.ROOT).trim()
        // 1. Exact keyword match across all kinds prevents substring/order shadowing
        for (rule in kindRules) {
            if (normalizedKey in rule.rawKeywords) {
                return rule.kind
            }
        }
        // 2. Bounded word match for ASCII keywords + substring match for CJK/non-ASCII keywords
        for (rule in kindRules) {
            if (rule.asciiWordPatterns.any { it.containsMatchIn(normalizedKey) } ||
                rule.nonAsciiKeywords.any { normalizedKey.contains(it) }
            ) {
                return rule.kind
            }
        }
        if (urlInTextRegex.containsMatchIn(rawValue)) return EventDetailKind.LINK
        if (emailRegex.containsMatchIn(rawValue)) return EventDetailKind.CONTACT
        if (allowValueKeywordFallback && rawValue.isNotBlank()) {
            val normalizedValue = rawValue.lowercase(Locale.ROOT).trim()
            for (rule in kindRules) {
                if (rule.asciiWordPatterns.any { it.containsMatchIn(normalizedValue) } ||
                    rule.nonAsciiKeywords.any { normalizedValue.contains(it) }
                ) {
                    return rule.kind
                }
            }
        }
        return EventDetailKind.OTHER
    }

    private fun stripControlChars(raw: String): String = raw.replace(controlAndBidiRegex, " ")

    /** Normalizes single-line display text by removing control/Bidi chars, collapsing whitespace, and bounding length. */
    fun sanitizeSingleLine(raw: String, maxChars: Int): String {
        val bounded = raw.take((maxChars * 4).coerceAtLeast(1024))
        return stripControlChars(bounded).replace(whitespaceRegex, " ").trim().take(maxChars)
    }

    /** Normalizes multi-line display text by stripping Bidi/control chars while preserving line breaks and bounding length. */
    fun sanitizeMultiLine(raw: String, maxChars: Int = MAX_SECTION_CONTENT_CHARS): String {
        val bounded = raw.take((maxChars * 4).coerceAtLeast(1024))
        return bounded.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(multiLineControlAndBidiRegex, "")
            .trim()
            .take(maxChars)
    }
}
