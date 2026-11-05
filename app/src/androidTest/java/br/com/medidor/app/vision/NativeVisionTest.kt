package br.com.medidor.app.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.medidor.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class NativeVisionTest {
    @Test fun boundaryDetectionRunsWithThePackagedNativeLibrary() {
        val bitmap = Bitmap.createBitmap(200, 160, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawRect(45f, 35f, 155f, 125f, Paint().apply { color = Color.rgb(40, 70, 90) })
        }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val boundary = VisualBoundaryDetector.find(pixels, bitmap.width, bitmap.height)
            assertNotNull(boundary)
            assertEquals(4, boundary!!.corners.size)
            assertTrue(boundary.region.minX in 42..48)
            assertTrue(boundary.region.maxY in 122..128)
        } finally { bitmap.recycle() }
    }

    @Test fun recognizesWallsFromAScaledPlan() {
        val bitmap = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { color = Color.BLACK; strokeWidth = 9f }
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawLine(80f, 80f, 230f, 80f, paint)
            drawLine(330f, 80f, 520f, 80f, paint)
            paint.strokeWidth = 1f
            drawLine(230f, 78f, 330f, 78f, paint)
            drawLine(230f, 82f, 330f, 82f, paint)
            paint.strokeWidth = 9f
            drawLine(80f, 80f, 80f, 420f, paint)
            drawLine(520f, 80f, 520f, 420f, paint)
            drawLine(80f, 420f, 270f, 420f, paint)
            drawLine(330f, 420f, 520f, 420f, paint)
        }
        val floor = PlanFloor("Teste", imageWidth = 600, imageHeight = 500, knownMeters = 4.4,
            referenceStart = PlanPoint(80f / 600, 80f / 500), referenceEnd = PlanPoint(520f / 600, 80f / 500))
        try {
            val draft = PlanImageRecognition.detect(bitmap, floor)
            assertTrue(draft.walls.size >= 4)
            assertTrue(draft.openings.any { it.type == OpeningType.DOOR })
            assertTrue(draft.openings.any { it.type == OpeningType.WINDOW })
        } finally { bitmap.recycle() }
    }

    @Test fun reconstructsNonPlanarGeometryFromThreeRenderedPhotographs() {
        val model = PhotoReconstruction.reconstruct(listOf(0, 1, 2), 60.0,
            load = { slot -> cubePhoto((slot - 1) * .22) }, progress = {})
        model.validate()
        assertTrue(model.registeredPhotos >= 2)
        assertTrue(model.triangles.size >= 10)
        assertTrue(model.vertices.maxOf { it.z } - model.vertices.minOf { it.z } > .02)
    }

    @Test fun identicalPhotographsDoNotCreateInventedDepth() {
        val result = runCatching {
            PhotoReconstruction.reconstruct(listOf(0, 1, 2), 60.0,
                load = { cubePhoto(0.0) }, progress = {})
        }
        assertTrue(result.isFailure)
    }

    private fun cubePhoto(angle: Double): Bitmap {
        val camera = doubleArrayOf(sin(angle) * 4.5, 1.2, cos(angle) * 4.5)
        fun normalized(v: DoubleArray): DoubleArray {
            val norm = sqrt(v.sumOf { it * it })
            return v.map { it / norm }.toDoubleArray()
        }
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        val forward = normalized(camera.map { -it }.toDoubleArray())
        val right = normalized(cross(forward, doubleArrayOf(0.0, 1.0, 0.0)))
        val down = cross(forward, right)
        val focal = 512.0 / (2 * tan(Math.PI / 6))
        fun project(point: DoubleArray): Triple<Float, Float, Double> {
            val relative = DoubleArray(3) { point[it] - camera[it] }
            fun dot(axis: DoubleArray) = axis.indices.sumOf { axis[it] * relative[it] }
            val z = dot(forward)
            return Triple((256 + focal * dot(right) / z).toFloat(),
                (256 + focal * dot(down) / z).toFloat(), z)
        }
        val vertices = listOf(
            doubleArrayOf(-1.0,-1.0,-1.0), doubleArrayOf(1.0,-1.0,-1.0),
            doubleArrayOf(1.0,1.0,-1.0), doubleArrayOf(-1.0,1.0,-1.0),
            doubleArrayOf(-1.0,-1.0,1.0), doubleArrayOf(1.0,-1.0,1.0),
            doubleArrayOf(1.0,1.0,1.0), doubleArrayOf(-1.0,1.0,1.0))
        val faces = listOf(listOf(0,1,2,3), listOf(4,5,6,7), listOf(0,4,7,3),
            listOf(1,5,6,2), listOf(3,2,6,7), listOf(0,1,5,4))
        val output = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(220, 225, 220))
        faces.withIndex().sortedByDescending { (_, face) -> face.map { project(vertices[it]).third }.average() }
            .forEach { (index, face) ->
                val texture = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                try {
                    val surface = Canvas(texture)
                    surface.drawColor(Color.rgb(170, 170, 160))
                    val random = Random(index + 132)
                    val paint = Paint()
                    repeat(1200) {
                        paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                        val x = random.nextInt(250).toFloat()
                        val y = random.nextInt(250).toFloat()
                        surface.drawRect(x, y, x + random.nextInt(3, 12), y + random.nextInt(3, 12), paint)
                    }
                    val source = floatArrayOf(0f,0f,256f,0f,256f,256f,0f,256f)
                    val target = FloatArray(8)
                    face.forEachIndexed { corner, vertex ->
                        val point = project(vertices[vertex])
                        target[corner * 2] = point.first
                        target[corner * 2 + 1] = point.second
                    }
                    val transform = Matrix()
                    assertTrue(transform.setPolyToPoly(source, 0, target, 0, 4))
                    canvas.drawBitmap(texture, transform, Paint(Paint.FILTER_BITMAP_FLAG))
                } finally { texture.recycle() }
            }
        return output
    }
}
