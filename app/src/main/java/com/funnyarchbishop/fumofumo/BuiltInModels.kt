package com.funnyarchbishop.fumofumo

/**
 * 内置在 APK 里的模型清单（assets 下的 models 目录，全是 glb）。
 *
 * 中文名用东方 Project 的通行译名；原名保留 Sketchfab 作者写的标题，
 * 作者与授权信息见 [author] / [license]，界面上要一起展示（CC BY 4.0 要求署名）。
 */
data class BuiltInModel(
    /** assets 内的路径 */
    val asset: String,
    /** 中文名 */
    val name: String,
    /** 作者写的原名（日文） */
    val originalTitle: String,
    /** 作者（Sketchfab 用户名） */
    val author: String,
    /** 三角面数，用来提示性能 */
    val faces: Int
) {
    val license: String get() = "CC BY 4.0"

    /** 列表里显示的副标题 */
    val subtitle: String get() = "$originalTitle · $author · ${"%,d".format(faces)} 面"
}

object BuiltInModels {

    private const val DIR = "models/"

    /**
     * 首次启动自动导入的默认模型。
     * 古明地恋用的是 2048×2048 贴图的版本（Sketchfab 直接下载的只有 1024×1024，
     * 几何完全一样，只是贴图糊一点）。
     */
    const val DEFAULT_ASSET = "${DIR}project-koishi-komeiji-fumo.glb"

    fun default(): BuiltInModel = byAsset(DEFAULT_ASSET) ?: ALL.first()

    /** 按角色名排序，方便找 */
    val ALL: List<BuiltInModel> = listOf(
        BuiltInModel("$DIR/project-reimu-fumo-3d-scan.glb",
            "博丽灵梦", "東方 Project - Reimu Fumo (3D scan)", "Renafox", 9999),
        BuiltInModel("$DIR/project-v2-reimu-hakurei-fumo-3d-scan.glb",
            "博丽灵梦（V2 重制）", "東方 Project - V2 Reimu Hakurei Fumo (3D scan)", "Renafox", 9999),
        BuiltInModel("$DIR/project-cirno-fumo-3d-scan.glb",
            "琪露诺", "東方 Project - Cirno Fumo (3D scan)", "Renafox", 10000),
        BuiltInModel("$DIR/project-mokou-fujiwara-fumo-3d-scan.glb",
            "藤原妹红", "東方 Project - Mokou Fujiwara Fumo (3D scan)", "Renafox", 10000),
        BuiltInModel("$DIR/project-v2-youmu-konpaku-fumo-3d-scan.glb",
            "魂魄妖梦", "東方 Project - V2 Youmu Konpaku Fumo (3D scan)", "Renafox", 9999),
        BuiltInModel("$DIR/project-yuuka-fumo-3d-scan.glb",
            "风见幽香", "東方 Project - Yuuka Fumo (3D scan)", "Renafox", 9999),
        BuiltInModel("$DIR/project-inu-sakuya-doll-3d-scan.glb",
            "犬咲夜", "東方 Project - Inu Sakuya Doll (3D scan)", "Renafox", 9999),
        BuiltInModel("$DIR/project-yuyuko-fumo-3d-reconstruction.glb",
            "西行寺幽幽子", "東方Project Yuyuko fumo (3D reconstruction)", "KBMollysuh", 27077),
        BuiltInModel("$DIR/project-v2-yuyuko-saigyouji-fumo-3d-scan.glb",
            "西行寺幽幽子（V2 重制）", "東方 Project - V2 Yuyuko Saigyouji Fumo (3D scan)", "Renafox", 10000),
        BuiltInModel("$DIR/remilia-scarlet-fumo-3d-scan.glb",
            "蕾米莉亚·斯卡雷特", "Remilia scarlet fumo (3D scan)", "scarletfumo", 11135),
        BuiltInModel("$DIR/flandre-scarlet-fumo-free-3d-scan.glb",
            "芙兰朵露·斯卡雷特", "Flandre Scarlet Fumo (Free 3D Scan)", "Renafox", 10134),
        BuiltInModel("$DIR/project-shion-yorigami-fumo-3d-scan.glb",
            "依神紫苑", "東方 Project - Shion Yorigami Fumo (3D scan)", "Renafox", 10074),
        BuiltInModel("$DIR/project-koishi-komeiji-fumo.glb",
            "古明地恋", "東方Project Koishi Komeiji Fumo 古明地 こいし ふも", "203Null", 97421),
        BuiltInModel("$DIR/project-satori-komeiji-fumo-3d-scan.glb",
            "古明地觉", "東方 Project - Satori Komeiji Fumo (3D scan)", "DAsh9986", 42371),
        BuiltInModel("$DIR/project-sanae-kochiya-fumo.glb",
            "东风谷早苗", "東方Project Sanae Kochiya Fumo 東風谷 早苗 ふも", "203Null", 49903),
        BuiltInModel("$DIR/project-suwako-moriya-fumo.glb",
            "洩矢诹访子", "東方Project Suwako Moriya Fumo 洩矢 諏訪子 ふも", "203Null", 50000),
        BuiltInModel("$DIR/project-junko-fumo.glb",
            "纯狐", "東方Project Junko Fumo 純狐 ふも", "203Null", 50056),
        BuiltInModel("$DIR/project-rumia-fumo.glb",
            "露米娅", "東方Project Rumia Fumo ルーミア ふも", "203Null", 99862),
        BuiltInModel("$DIR/project-nitori-kawashiro-fumo.glb",
            "河城荷取", "東方Project Nitori Kawashiro Fumo 河城 にとり ふも", "203Null", 49998),
        BuiltInModel("$DIR/project-hong-meiling-fumo.glb",
            "红美铃", "東方Project Hong Meiling Fumo 紅美鈴 ふも", "203Null", 50144),
        BuiltInModel("$DIR/project-keiki-haniyasushin-fumo.glb",
            "埴安神袿姬", "東方Project Keiki Haniyasushin Fumo 埴安神 袿姫 ふも", "203Null", 50000)
    )

    fun byAsset(asset: String): BuiltInModel? = ALL.firstOrNull { it.asset == asset }

    /** 生成完整署名文本（CC BY 4.0 要求） */
    fun attributionText(): String = buildString {
        appendLine("内置模型均来自 Sketchfab，作者以 CC BY 4.0 授权：")
        appendLine()
        ALL.forEach { m ->
            appendLine("· ${m.name}")
            appendLine("  原名：${m.originalTitle}")
            appendLine("  作者：${m.author}")
            appendLine("  授权：CC BY 4.0")
        }
        appendLine()
        appendLine("完整清单与出处链接见：")
        appendLine("github.com/FunnyArchbishop/Fumofumo_Models")
    }
}
