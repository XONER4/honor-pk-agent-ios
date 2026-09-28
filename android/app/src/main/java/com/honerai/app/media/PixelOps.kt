package com.honerai.app.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Обработка пикселей ARGB без Android (проверяется JVM-тестами): стековое размытие,
 * резкость свёрткой 3×3 и маска объекта для удаления фона.
 */
object PixelOps {
    /** Стековое размытие (Stack Blur) по всем четырём каналам, на месте. */
    fun stackBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val r = radius.coerceIn(1, 254)
        if (width < 2 || height < 2) return
        val buffer = IntArray(max(width, height))
        val line = IntArray(max(width, height))
        for (y in 0 until height) {
            val offset = y * width
            System.arraycopy(pixels, offset, line, 0, width)
            blurLine(line, width, r, buffer)
            System.arraycopy(buffer, 0, pixels, offset, width)
        }
        for (x in 0 until width) {
            for (y in 0 until height) line[y] = pixels[y * width + x]
            blurLine(line, height, r, buffer)
            for (y in 0 until height) pixels[y * width + x] = buffer[y]
        }
    }

    /** Треугольное ядро скользящими суммами: сумма, «входящие» и «уходящие» пиксели. */
    private fun blurLine(src: IntArray, n: Int, r: Int, out: IntArray) {
        val div = (r + 1) * (r + 1)
        val last = n - 1
        var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0
        var inA = 0; var inR = 0; var inG = 0; var inB = 0
        var outA = 0; var outR = 0; var outG = 0; var outB = 0
        for (i in -r..r) {
            val p = src[i.coerceIn(0, last)]
            val w = r + 1 - kotlin.math.abs(i)
            val a = p ushr 24; val rr = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            sumA += a * w; sumR += rr * w; sumG += g * w; sumB += b * w
            if (i <= 0) { outA += a; outR += rr; outG += g; outB += b } else { inA += a; inR += rr; inG += g; inB += b }
        }
        for (x in 0 until n) {
            out[x] = ((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
            sumA -= outA; sumR -= outR; sumG -= outG; sumB -= outB
            val left = src[(x - r).coerceIn(0, last)]
            outA -= left ushr 24; outR -= (left shr 16) and 255; outG -= (left shr 8) and 255; outB -= left and 255
            val right = src[(x + r + 1).coerceIn(0, last)]
            inA += right ushr 24; inR += (right shr 16) and 255; inG += (right shr 8) and 255; inB += right and 255
            sumA += inA; sumR += inR; sumG += inG; sumB += inB
            val center = src[(x + 1).coerceIn(0, last)]
            val ca = center ushr 24; val cr = (center shr 16) and 255; val cg = (center shr 8) and 255; val cb = center and 255
            inA -= ca; inR -= cr; inG -= cg; inB -= cb
            outA += ca; outR += cr; outG += cg; outB += cb
        }
    }

    /** Резкость: свёртка 3×3 (центр 1+4k, соседи −k). Альфа не меняется. */
    fun sharpen(pixels: IntArray, width: Int, height: Int, amount: Float = 0.45f): IntArray {
        val out = pixels.copyOf()
        if (width < 3 || height < 3) return out
        val center = 1 + 4 * amount
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val c = pixels[i]
                val up = pixels[i - width]; val down = pixels[i + width]; val left = pixels[i - 1]; val right = pixels[i + 1]
                fun channel(shift: Int): Int {
                    val v = center * ((c shr shift) and 255) - amount * (((up shr shift) and 255) + ((down shr shift) and 255) +
                        ((left shr shift) and 255) + ((right shr shift) and 255))
                    return v.roundToInt().coerceIn(0, 255)
                }
                out[i] = (c and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
        }
        return out
    }
}

/**
 * Маска объекта без нейросети: модель цвета фона по краям снимка (k-средних в пространстве Lab),
 * заливка похожих цветов от края с допуском, дыры внутри объекта, очистка мелочи и мягкий край.
 * Возвращает прозрачность 0…1 для каждого пикселя или null, если объект не найден.
 */
object SubjectMask {
    private const val CLUSTERS = 6

    private class Cluster(var l: Float, var a: Float, var b: Float) {
        var count = 0
        var spread = 0f
        val sides = IntArray(4)
        var threshold = 0f
    }

    fun compute(pixels: IntArray, width: Int, height: Int): FloatArray? {
        val n = width * height
        if (width < 8 || height < 8) return null
        val lab = toLab(pixels)
        val L = lab[0]; val A = lab[1]; val B = lab[2]

        // Образцы с краёв (полоса ~3 %) с отметкой стороны: 0 — верх, 1 — низ, 2 — лево, 3 — право.
        val band = max(2, (min(width, height) * 0.03f).roundToInt())
        val sampleIndex = ArrayList<Int>()
        val sampleSide = ArrayList<Int>()
        val step = max(1, (2 * (width + height) * band) / 6000)
        var counter = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val side = when {
                    y < band -> 0
                    y >= height - band -> 1
                    x < band -> 2
                    x >= width - band -> 3
                    else -> -1
                }
                if (side < 0) continue
                if (counter++ % step != 0) continue
                sampleIndex.add(y * width + x)
                sampleSide.add(side)
            }
        }
        if (sampleIndex.isEmpty()) return null
        val clusters = kMeans(sampleIndex, L, A, B)
        val assignment = IntArray(sampleIndex.size) { nearestCluster(clusters, L[sampleIndex[it]], A[sampleIndex[it]], B[sampleIndex[it]]) }
        val sideTotals = IntArray(4)
        for (i in sampleIndex.indices) {
            val c = clusters[assignment[i]]
            val idx = sampleIndex[i]
            c.count++
            c.sides[sampleSide[i]]++
            sideTotals[sampleSide[i]]++
            c.spread += sq(L[idx] - c.l) + sq(A[idx] - c.a) + sq(B[idx] - c.b)
        }
        val total = sampleIndex.size
        // Фон — цвета, которые встречаются хотя бы на двух сторонах (объект обычно касается одной).
        var background = clusters.filter { c ->
            if (c.count == 0) return@filter false
            val sidesPresent = (0 until 4).count { s -> sideTotals[s] > 0 && c.sides[s] >= sideTotals[s] * 0.12f }
            (c.count >= total * 0.03f && sidesPresent >= 2) || c.count >= total * 0.5f
        }
        if (background.isEmpty()) background = listOf(clusters.maxBy { it.count })
        for (c in background) {
            val sigma = sqrt(c.spread / max(1, c.count))
            c.threshold = (2.5f * sigma + 6f).coerceIn(10f, 28f)
        }
        val bgArray = background.toTypedArray()
        val distance = FloatArray(n) { i ->
            var best = Float.MAX_VALUE
            for (c in bgArray) {
                val d = sqrt(sq(L[i] - c.l) + sq(A[i] - c.a) + sq(B[i] - c.b)) / c.threshold
                if (d < best) best = d
            }
            best
        }

        // Заливка от края: похожие на фон цвета или плавный переход (небо, градиент стены).
        val isBackground = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        fun seed(i: Int) {
            if (!isBackground[i] && distance[i] < 1f) { isBackground[i] = true; queue[tail++] = i }
        }
        for (x in 0 until width) { seed(x); seed((height - 1) * width + x) }
        for (y in 0 until height) { seed(y * width); seed(y * width + width - 1) }
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            val y = i / width
            fun visit(j: Int) {
                if (isBackground[j]) return
                val d = distance[j]
                val joins = d < 1f || (d < 1.8f && sqrt(sq(L[i] - L[j]) + sq(A[i] - A[j]) + sq(B[i] - B[j])) < 5f)
                if (joins) { isBackground[j] = true; queue[tail++] = j }
            }
            if (x > 0) visit(i - 1)
            if (x < width - 1) visit(i + 1)
            if (y > 0) visit(i - width)
            if (y < height - 1) visit(i + width)
        }

        // Замкнутые участки фона внутри объекта (просвет между рукой и телом): крупные и очень похожие на фон.
        val labels = IntArray(n) { -1 }
        val minHole = max(16, (n * 0.002f).toInt())
        for (start in 0 until n) {
            if (isBackground[start] || labels[start] != -1 || distance[start] >= 0.5f) continue
            val component = floodComponent(start, width, height, labels, queue) { j -> !isBackground[j] && distance[j] < 0.5f }
            if (component.size >= minHole) for (j in component) isBackground[j] = true
        }

        // Оставляем крупные части объекта, мелкие островки считаем фоном.
        labels.fill(-1)
        val components = ArrayList<IntArray>()
        for (start in 0 until n) {
            if (isBackground[start] || labels[start] != -1) continue
            components.add(floodComponent(start, width, height, labels, queue) { j -> !isBackground[j] })
        }
        if (components.isEmpty()) return null
        val largest = components.maxOf { it.size }
        for (component in components) {
            if (component.size < largest * 0.2f && component.size < n * 0.015f) for (j in component) isBackground[j] = true
        }
        val foreground = isBackground.count { !it }
        val coverage = foreground.toFloat() / n
        if (coverage < 0.004f || coverage > 0.985f) return null

        // Мягкий край: сглаживание маски и два прохода размытия.
        var mask = FloatArray(n) { if (isBackground[it]) 0f else 1f }
        mask = majority(mask, width, height)
        val feather = max(1, (min(width, height) / 250f).roundToInt())
        boxBlur(mask, width, height, feather)
        boxBlur(mask, width, height, feather)
        for (i in 0 until n) mask[i] = ((mask[i] - 0.5f) * 1.6f + 0.5f).coerceIn(0f, 1f)
        return mask
    }

    private fun sq(v: Float) = v * v

    private inline fun floodComponent(
        start: Int, width: Int, height: Int, labels: IntArray, queue: IntArray, crossinline member: (Int) -> Boolean,
    ): IntArray {
        var head = 0
        var tail = 0
        labels[start] = start
        queue[tail++] = start
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            val y = i / width
            if (x > 0 && labels[i - 1] == -1 && member(i - 1)) { labels[i - 1] = start; queue[tail++] = i - 1 }
            if (x < width - 1 && labels[i + 1] == -1 && member(i + 1)) { labels[i + 1] = start; queue[tail++] = i + 1 }
            if (y > 0 && labels[i - width] == -1 && member(i - width)) { labels[i - width] = start; queue[tail++] = i - width }
            if (y < height - 1 && labels[i + width] == -1 && member(i + width)) { labels[i + width] = start; queue[tail++] = i + width }
        }
        return queue.copyOf(tail)
    }

    /** Большинство в окне 3×3: убирает «зубцы» и одиночные точки на краю маски. */
    private fun majority(mask: FloatArray, width: Int, height: Int): FloatArray {
        val out = mask.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var sum = 0f
                for (dy in -1..1) for (dx in -1..1) sum += mask[(y + dy) * width + x + dx]
                out[y * width + x] = if (sum >= 5f) 1f else 0f
            }
        }
        return out
    }

    private fun boxBlur(mask: FloatArray, width: Int, height: Int, radius: Int) {
        val tmp = FloatArray(max(width, height))
        val window = 2 * radius + 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (i in -radius..radius) sum += mask[row + i.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                tmp[x] = sum / window
                sum += mask[row + (x + radius + 1).coerceIn(0, width - 1)] - mask[row + (x - radius).coerceIn(0, width - 1)]
            }
            System.arraycopy(tmp, 0, mask, row, width)
        }
        for (x in 0 until width) {
            var sum = 0f
            for (i in -radius..radius) sum += mask[i.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                tmp[y] = sum / window
                sum += mask[(y + radius + 1).coerceIn(0, height - 1) * width + x] - mask[(y - radius).coerceIn(0, height - 1) * width + x]
            }
            for (y in 0 until height) mask[y * width + x] = tmp[y]
        }
    }

    private fun kMeans(samples: List<Int>, L: FloatArray, A: FloatArray, B: FloatArray): List<Cluster> {
        // Начальные центры: первый образец, затем каждый раз самый далёкий от выбранных.
        val centers = ArrayList<Cluster>()
        centers.add(Cluster(L[samples[0]], A[samples[0]], B[samples[0]]))
        val nearest = FloatArray(samples.size) { Float.MAX_VALUE }
        while (centers.size < min(CLUSTERS, samples.size)) {
            val last = centers.last()
            var farIndex = 0
            var farDistance = -1f
            for (i in samples.indices) {
                val s = samples[i]
                val d = sq(L[s] - last.l) + sq(A[s] - last.a) + sq(B[s] - last.b)
                if (d < nearest[i]) nearest[i] = d
                if (nearest[i] > farDistance) { farDistance = nearest[i]; farIndex = i }
            }
            if (farDistance < 4f) break
            val s = samples[farIndex]
            centers.add(Cluster(L[s], A[s], B[s]))
        }
        val sumL = FloatArray(centers.size); val sumA = FloatArray(centers.size); val sumB = FloatArray(centers.size)
        val counts = IntArray(centers.size)
        repeat(8) {
            sumL.fill(0f); sumA.fill(0f); sumB.fill(0f); counts.fill(0)
            for (s in samples) {
                val c = nearestCluster(centers, L[s], A[s], B[s])
                sumL[c] += L[s]; sumA[c] += A[s]; sumB[c] += B[s]; counts[c]++
            }
            for (c in centers.indices) if (counts[c] > 0) {
                centers[c].l = sumL[c] / counts[c]; centers[c].a = sumA[c] / counts[c]; centers[c].b = sumB[c] / counts[c]
            }
        }
        return centers
    }

    private fun nearestCluster(clusters: List<Cluster>, l: Float, a: Float, b: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in clusters.indices) {
            val c = clusters[i]
            val d = sq(l - c.l) + sq(a - c.a) + sq(b - c.b)
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        return best
    }

    /** sRGB → CIE Lab (D65). */
    private fun toLab(pixels: IntArray): Array<FloatArray> {
        val linear = FloatArray(256) { v ->
            val c = v / 255.0
            (if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)).toFloat()
        }
        val n = pixels.size
        val L = FloatArray(n); val A = FloatArray(n); val B = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            val r = linear[(p shr 16) and 255]; val g = linear[(p shr 8) and 255]; val b = linear[p and 255]
            val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
            val fx = f(x); val fy = f(y); val fz = f(z)
            L[i] = 116f * fy - 16f
            A[i] = 500f * (fx - fy)
            B[i] = 200f * (fy - fz)
        }
        return arrayOf(L, A, B)
    }

    private fun f(t: Float): Float = if (t > 0.008856f) Math.cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f
}
