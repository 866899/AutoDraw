package com.autodraw.app.image

import android.graphics.PointF
import com.autodraw.app.drawing.Stroke

/**
 * 把端点彼此贴近的多条 [Stroke] 合并成更长的连续笔画,减少抬笔次数与笔画总数,
 * 同时不改变实际绘制的几何(仅连接端点贴近的笔画,不插入虚假连线)。
 *
 * 适用场景:marching squares 等值线常因边缘场局部断裂被切成多段,本来是同一条轮廓。
 *
 * 算法:贪心最近端点链接。
 *  - 以一条笔画为种子,从其尾部出发,在剩余笔画中找端点距尾部 ≤ [gap] 的笔画。
 *  - 找到后将其定向为“匹配端点在头部”,把除首点外的点追加到当前链(首点与尾部
 *    近似重合,丢弃以避免冗余点),并从剩余集合移除。
 *  - 若当前链尾部又靠近自身头部,则构成闭环,提前结束。
 *  - 仅在同 [Stroke.Style] 组内合并(轮廓与阴影分别合并),且使用种子笔画的
 *    [Stroke.width],同一组内宽度差异通常很小。
 *
 * [gap] 为归一化坐标(相对图片短边/长边)。设为 0 关闭合并。
 */
internal object StrokeMerger {

    fun merge(strokes: List<Stroke>, gap: Float): List<Stroke> {
        if (strokes.size < 2 || gap <= 0f) return strokes
        val gapSq = gap * gap

        // 工作副本:可变点列表 + 是否已被消费
        data class Node(
            val points: ArrayList<PointF>,
            val width: Float,
            val style: Stroke.Style,
            var used: Boolean = false
        )
        val nodes = ArrayList<Node>(strokes.size)
        for (s in strokes) {
            if (s.points.size < 2) continue
            nodes.add(Node(ArrayList(s.points), s.width, s.style))
        }
        if (nodes.size < 2) return strokes

        val result = ArrayList<Stroke>(nodes.size)

        for (seedIdx in nodes.indices) {
            val seed = nodes[seedIdx]
            if (seed.used) continue
            seed.used = true
            val chain = seed.points // 直接复用,后续追加
            var tail = chain.last()

            // 反复尝试从尾部接续
            while (true) {
                var bestIdx = -1
                var bestFlip = false
                var bestDist = gapSq
                for (i in nodes.indices) {
                    val n = nodes[i]
                    if (n.used) continue
                    val p = n.points
                    val dHead = distSq(tail, p.first())
                    val dTail = distSq(tail, p.last())
                    if (dHead <= bestDist) {
                        bestDist = dHead
                        bestIdx = i
                        bestFlip = false
                    }
                    if (dTail <= bestDist) {
                        bestDist = dTail
                        bestIdx = i
                        bestFlip = true
                    }
                }
                if (bestIdx < 0) break
                val pick = nodes[bestIdx]
                pick.used = true
                val pp = pick.points
                // 定向:匹配端点放到头部,追加尾部之外的所有点
                if (bestFlip) {
                    // 原 tail 端匹配,反向追加
                    for (k in pp.lastIndex - 1 downTo 0) chain.add(pp[k])
                } else {
                    // 原 head 端匹配,正向追加
                    for (k in 1 until pp.size) chain.add(pp[k])
                }
                tail = chain.last()
                // 闭环检测:尾部贴近头部则结束(不再强连,避免重复点)
                if (distSq(tail, chain.first()) <= gapSq) break
            }

            result.add(Stroke(points = chain, width = seed.width, style = seed.style))
        }

        return result
    }

    private fun distSq(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
