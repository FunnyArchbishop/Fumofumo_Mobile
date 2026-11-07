package com.funnyarchbishop.fumofumo

import android.graphics.Bitmap

/**
 * 摊平后的三角面数据：每个角一份顶点，不共享（和桌面版 WINobj 的处理方式一致）。
 * UV 一律采用 glTF 约定：原点在贴图左上角。
 */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val texture: Bitmap?,
    val min: FloatArray,
    val max: FloatArray,
    val source: String = ""
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = positions.size / 9
}
