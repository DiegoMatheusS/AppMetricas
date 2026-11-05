package br.com.medidor.app.vision

import android.graphics.Bitmap
import br.com.medidor.app.domain.MeshGeometry
import br.com.medidor.app.domain.MeshTriangle
import br.com.medidor.app.domain.Point3d
import br.com.medidor.app.domain.ReconstructedMesh
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Subdiv2D
import kotlin.math.*

/** Incremental structure from motion. Every vertex is triangulated from matched photographs. */
internal object PhotoReconstruction {
    private data class View(
        val slot: Int, val width: Int, val height: Int, val focal: Double,
        val keypoints: List<KeyPoint>, val descriptors: Mat,
        val tracks: IntArray, var pose: DoubleArray? = null
    ) {
        fun normalized(index: Int): Point = keypoints[index].pt.let {
            Point((it.x - width / 2.0) / focal, (it.y - height / 2.0) / focal)
        }
    }
    private data class Match(val a: Int, val b: Int)
    private val identityPose = doubleArrayOf(1.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0, 0.0,0.0,1.0,0.0)

    fun reconstruct(slots: List<Int>, fieldOfView: Double, load: (Int) -> Bitmap?,
                    progress: (String) -> Unit): ReconstructedMesh {
        require(slots.size in 3..20) { "Capture pelo menos três fotografias sobrepostas." }
        require(fieldOfView.isFinite() && fieldOfView in 25.0..100.0) { "Campo de visão inválido." }
        NativeVision.requireAvailable()
        return MatScope().use { scope ->
            val views = slots.mapNotNull { slot ->
                progress("Localizando detalhes na foto ${slot + 1}…")
                val bitmap = load(slot) ?: return@mapNotNull null
                try { extract(bitmap, slot, fieldOfView, scope) } finally { bitmap.recycle() }
            }
            require(views.size >= 3) { "Faltam fotos com detalhes suficientes. Fotografe mais perto e com boa iluminação." }
            val matches = mutableMapOf<Pair<Int, Int>, List<Match>>()
            fun pair(a: Int, b: Int): List<Match> = matches.getOrPut(a to b) { match(views[a], views[b]) }
            val candidates = views.indices.flatMap { a ->
                (a + 1..minOf(a + 2, views.lastIndex)).map { b -> a to b }
            }.sortedByDescending { (a, b) -> pair(a, b).size }
            val vertices = mutableListOf<Point3d>()
            var initialized = false
            for ((a, b) in candidates.take(10)) {
                progress("Calculando profundidade entre as fotos ${views[a].slot + 1} e ${views[b].slot + 1}…")
                if (initialize(views[a], views[b], pair(a, b), vertices)) {
                    initialized = true
                    break
                }
            }
            require(initialized) { "Não encontrei profundidade consistente. Mova a câmera entre as fotos mantendo o objeto imóvel e visível." }
            var added: Boolean
            do {
                added = false
                for (next in views.indices.filter { views[it].pose == null }) {
                    progress("Alinhando foto ${views[next].slot + 1} ao modelo…")
                    val referencePairs = views.indices.filter { views[it].pose != null }
                        .associateWith { reference -> pair(reference, next) }
                    if (!register(views[next], referencePairs.map { (reference, found) -> views[reference] to found }, vertices))
                        continue
                    referencePairs.forEach { (reference, found) ->
                        triangulate(views[reference], views[next], found, vertices)
                    }
                    added = true
                }
            } while (added && views.any { it.pose == null })
            progress("Construindo a superfície 3D…")
            buildMesh(views, vertices, slots.size).also { it.validate() }
        }
    }

    private fun extract(bitmap: Bitmap, slot: Int, fov: Double, scope: MatScope): View? {
        val rgba = scope.own(Mat())
        val gray = scope.own(Mat())
        Utils.bitmapToMat(bitmap, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val mask = scope.own(Mat.zeros(gray.size(), CvType.CV_8UC1))
        // Avoid the outer background. Capture guidance asks to fill this central region.
        Imgproc.rectangle(mask, Point(gray.cols() * .08, gray.rows() * .08),
            Point(gray.cols() * .92, gray.rows() * .92), Scalar(255.0), -1)
        val keypoints = scope.own(MatOfKeyPoint())
        val descriptors = scope.own(Mat())
        val detector = ORB.create(1800)
        try { detector.detectAndCompute(gray, mask, keypoints, descriptors) } finally { detector.clear() }
        val points = keypoints.toList()
        if (points.size < 50 || descriptors.empty()) return null
        val focal = gray.cols() / (2 * tan(Math.toRadians(fov) / 2))
        return View(slot, gray.cols(), gray.rows(), focal, points, descriptors, IntArray(points.size) { -1 })
    }

    private fun match(first: View, second: View): List<Match> {
        val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
        val forward = mutableListOf<MatOfDMatch>()
        val reverse = mutableListOf<MatOfDMatch>()
        return try {
            matcher.knnMatch(first.descriptors, second.descriptors, forward, 2)
            matcher.knnMatch(second.descriptors, first.descriptors, reverse, 2)
            val backward = reverse.mapNotNull { row ->
                val pair = row.toArray()
                pair.firstOrNull()?.takeIf { pair.size == 2 && it.distance < pair[1].distance * .75 }
                    ?.let { it.queryIdx to it.trainIdx }
            }.toMap()
            forward.mapNotNull { row ->
                val pair = row.toArray()
                pair.firstOrNull()?.takeIf { pair.size == 2 && it.distance < pair[1].distance * .75 &&
                    it.distance < 65 && backward[it.trainIdx] == it.queryIdx }
                    ?.let { Match(it.queryIdx, it.trainIdx) }
            }.take(1400)
        } finally {
            forward.forEach(Mat::release)
            reverse.forEach(Mat::release)
            matcher.clear()
        }
    }

    private fun initialize(first: View, second: View, matches: List<Match>, vertices: MutableList<Point3d>): Boolean {
        if (matches.size < 35) return false
        return MatScope().use { scope ->
            val p1 = scope.own(MatOfPoint2f(*matches.map { first.normalized(it.a) }.toTypedArray()))
            val p2 = scope.own(MatOfPoint2f(*matches.map { second.normalized(it.b) }.toTypedArray()))
            val camera = scope.own(Mat.eye(3, 3, CvType.CV_64F))
            val mask = scope.own(Mat())
            val essential = scope.own(Calib3d.findEssentialMat(p1, p2, camera,
                Calib3d.RANSAC, .999, 2.0 / minOf(first.focal, second.focal), 1200, mask))
            if (essential.rows() != 3 || essential.cols() != 3) return@use false
            val rotation = scope.own(Mat())
            val translation = scope.own(Mat())
            val count = Calib3d.recoverPose(essential, p1, p2, camera, rotation, translation, mask)
            if (count < 30 || count < matches.size * .45) return@use false
            first.pose = identityPose.copyOf()
            second.pose = pose(rotation, translation)
            val accepted = matches.filterIndexed { index, _ -> mask.get(index, 0)[0] != 0.0 }
            triangulate(first, second, accepted, vertices)
            if (vertices.size < 25) {
                vertices.clear()
                first.tracks.fill(-1)
                second.tracks.fill(-1)
                first.pose = null
                second.pose = null
                false
            } else true
        }
    }

    private fun register(view: View, references: List<Pair<View, List<Match>>>, vertices: List<Point3d>): Boolean {
        val correspondences = linkedMapOf<Int, Int>()
        references.forEach { (reference, matches) ->
            matches.forEach { match ->
                val track = reference.tracks[match.a]
                if (track >= 0 && track !in correspondences.values) correspondences.putIfAbsent(match.b, track)
            }
        }
        if (correspondences.size < 12) return false
        return MatScope().use { scope ->
            val entries = correspondences.entries.toList()
            val objectPoints = scope.own(MatOfPoint3f(*entries.map { (_, track) -> vertices[track].let {
                Point3(it.x, it.y, it.z) } }.toTypedArray()))
            val imagePoints = scope.own(MatOfPoint2f(*entries.map { view.keypoints[it.key].pt }.toTypedArray()))
            val camera = scope.own(Mat.eye(3, 3, CvType.CV_64F)).apply {
                put(0, 0, view.focal); put(1, 1, view.focal)
                put(0, 2, view.width / 2.0); put(1, 2, view.height / 2.0)
            }
            val distortion = scope.own(MatOfDouble())
            val rvec = scope.own(Mat())
            val tvec = scope.own(Mat())
            val inliers = scope.own(Mat())
            val valid = Calib3d.solvePnPRansac(objectPoints, imagePoints, camera, distortion, rvec, tvec,
                false, 150, 3f, .999, inliers, Calib3d.SOLVEPNP_EPNP)
            if (!valid || inliers.rows() < 12 || inliers.rows() < entries.size * .55) return@use false
            val rotation = scope.own(Mat())
            Calib3d.Rodrigues(rvec, rotation)
            view.pose = pose(rotation, tvec)
            for (row in 0 until inliers.rows()) {
                val entry = entries[inliers.get(row, 0)[0].toInt()]
                view.tracks[entry.key] = entry.value
            }
            true
        }
    }

    private fun triangulate(first: View, second: View, matches: List<Match>, vertices: MutableList<Point3d>) {
        val a = first.pose ?: return
        val b = second.pose ?: return
        val fresh = mutableListOf<Match>()
        for (match in matches) {
            val old = first.tracks[match.a]
            val next = second.tracks[match.b]
            when {
                old >= 0 && next < 0 && residual(second, vertices[old], match.b) < 3 -> second.tracks[match.b] = old
                next >= 0 && old < 0 && residual(first, vertices[next], match.a) < 3 -> first.tracks[match.a] = next
                old < 0 && next < 0 -> fresh += match
            }
        }
        if (fresh.isEmpty() || vertices.size >= 12_000) return
        MatScope().use { scope ->
            val p1 = scope.own(MatOfPoint2f(*fresh.map { first.normalized(it.a) }.toTypedArray()))
            val p2 = scope.own(MatOfPoint2f(*fresh.map { second.normalized(it.b) }.toTypedArray()))
            val projection1 = scope.own(Mat(3, 4, CvType.CV_64F)).apply { put(0, 0, *a) }
            val projection2 = scope.own(Mat(3, 4, CvType.CV_64F)).apply { put(0, 0, *b) }
            val homogeneous = scope.own(Mat())
            Calib3d.triangulatePoints(projection1, projection2, p1, p2, homogeneous)
            fresh.forEachIndexed { index, match ->
                val w = homogeneous.get(3, index)[0]
                if (!w.isFinite() || abs(w) < 1e-10 || vertices.size >= 12_000) return@forEachIndexed
                val point = Point3d(homogeneous.get(0, index)[0] / w,
                    homogeneous.get(1, index)[0] / w, homogeneous.get(2, index)[0] / w)
                if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite() ||
                    residual(first, point, match.a) > 3 || residual(second, point, match.b) > 3 ||
                    parallax(a, b, point) < .8) return@forEachIndexed
                first.tracks[match.a] = vertices.size
                second.tracks[match.b] = vertices.size
                vertices += point
            }
        }
    }

    private fun residual(view: View, point: Point3d, feature: Int): Double {
        val pose = view.pose ?: return Double.POSITIVE_INFINITY
        val z = pose[8] * point.x + pose[9] * point.y + pose[10] * point.z + pose[11]
        if (!z.isFinite() || z <= .001) return Double.POSITIVE_INFINITY
        val x = (pose[0] * point.x + pose[1] * point.y + pose[2] * point.z + pose[3]) / z
        val y = (pose[4] * point.x + pose[5] * point.y + pose[6] * point.z + pose[7]) / z
        val target = view.normalized(feature)
        return hypot(x - target.x, y - target.y) * view.focal
    }

    private fun parallax(a: DoubleArray, b: DoubleArray, point: Point3d): Double {
        fun ray(pose: DoubleArray): DoubleArray = doubleArrayOf(
            point.x + pose[0] * pose[3] + pose[4] * pose[7] + pose[8] * pose[11],
            point.y + pose[1] * pose[3] + pose[5] * pose[7] + pose[9] * pose[11],
            point.z + pose[2] * pose[3] + pose[6] * pose[7] + pose[10] * pose[11])
        val x = ray(a)
        val y = ray(b)
        val norm = sqrt(x.sumOf { it * it } * y.sumOf { it * it })
        if (norm < 1e-12) return 0.0
        return Math.toDegrees(acos((x.indices.sumOf { x[it] * y[it] } / norm).coerceIn(-1.0, 1.0)))
    }

    private fun pose(rotation: Mat, translation: Mat): DoubleArray = DoubleArray(12) { index ->
        if (index % 4 == 3) translation.get(index / 4, 0)[0]
        else rotation.get(index / 4, index % 4)[0]
    }

    private fun buildMesh(views: List<View>, points: List<Point3d>, supplied: Int): ReconstructedMesh {
        val span = maxOf(points.maxOf { it.x } - points.minOf { it.x },
            points.maxOf { it.y } - points.minOf { it.y }, points.maxOf { it.z } - points.minOf { it.z })
        val triangles = linkedMapOf<List<Int>, MeshTriangle>()
        for (view in views.filter { it.pose != null }) {
            val subdivision = Subdiv2D(Rect(0, 0, view.width, view.height))
            val observed = view.tracks.indices.filter { view.tracks[it] >= 0 }
            val inserted = linkedMapOf<Int, Int>()
            observed.forEach { feature ->
                val pixel = view.keypoints[feature].pt
                if (pixel.x > 0 && pixel.y > 0 && pixel.x < view.width && pixel.y < view.height) {
                    val vertex = subdivision.insert(pixel)
                    inserted.putIfAbsent(vertex, feature)
                }
            }
            MatOfFloat6().let { list ->
                try {
                    subdivision.getTriangleList(list)
                    val data = list.toArray()
                    for (start in data.indices step 6) {
                        val image = List(3) { offset -> Point(data[start + offset * 2].toDouble(), data[start + offset * 2 + 1].toDouble()) }
                        if (image.any { it.x < 0 || it.y < 0 || it.x >= view.width || it.y >= view.height }) continue
                        val features = image.map { pixel ->
                            inserted[subdivision.findNearest(pixel)] ?: -1
                        }
                        if (features.any { it < 0 } || features.distinct().size != 3) continue
                        val indices = features.map { view.tracks[it] }
                        if (indices.distinct().size != 3) continue
                        val pixelEdges = image.indices.map { i -> hypot(image[i].x - image[(i + 1) % 3].x, image[i].y - image[(i + 1) % 3].y) }
                        if (pixelEdges.max() > minOf(view.width, view.height) * .22) continue
                        if (!MeshGeometry.supportedTriangle(points[indices[0]], points[indices[1]], points[indices[2]], span * .3)) continue
                        triangles.putIfAbsent(indices.sorted(), MeshTriangle(indices[0], indices[1], indices[2]))
                        if (triangles.size >= 30_000) break
                    }
                } finally { list.release() }
            }
        }
        val converted = points.map { Point3d(it.x, it.z, -it.y) }
        return ReconstructedMesh(converted, triangles.values.toList(), views.count { it.pose != null }, supplied)
    }
}
