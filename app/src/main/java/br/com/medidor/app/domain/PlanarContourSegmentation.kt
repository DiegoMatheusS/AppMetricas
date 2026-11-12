package br.com.medidor.app.domain

import kotlin.math.abs

data class ContourPixel(val x: Int, val y: Int)

data class PlanarContourResult(
    val width: Int,
    val height: Int,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val pixelsInside: Int,
    val outline: List<ContourPixel>
) {
    val widthFraction: Double get() = (maxX-minX+1).toDouble()/width
    val heightFraction: Double get() = (maxY-minY+1).toDouble()/height
}

/**
 * Offline approximate color flood-fill from a user-selected seed.
 * Only an image-plane silhouette. Without physical reference at the same
 * distance, this CANNOT give real size, depth, or perimeter of 3D objects.
 */
object PlanarContourSegmentation {
    fun segment(pixels: IntArray, width: Int, height: Int,
                seedX: Int, seedY: Int, tolerance: Int = 32,
                bounds: ContourSearchBounds? = null): PlanarContourResult? {
        require(width in 8..512 && height in 8..512 && pixels.size == width*height)
        require(seedX in 0 until width && seedY in 0 until height)
        require(tolerance in 5..120)
        if (bounds != null) {
            require(bounds.right < width && bounds.bottom < height) {
                "Área de seleção fora do quadro."
            }
            if (!bounds.contains(seedX,seedY)) return null
        }
        val root = pixels[seedY*width + seedX]
        fun diff(value: Int): Int {
            val r = abs(((value ushr 16) and 255) - ((root ushr 16) and 255))
            val g = abs(((value ushr 8) and 255) - ((root ushr 8) and 255))
            val b = abs((value and 255) - (root and 255))
            return maxOf(r,g,b)
        }
        val visited = BooleanArray(width*height)
        val accepted = BooleanArray(width*height)
        val queue = IntArray(width*height)
        val origin = seedY*width + seedX
        var head = 0
        var tail = 1
        queue[0] = origin
        visited[origin] = true
        var minX = seedX
        var minY = seedY
        var maxX = seedX
        var maxY = seedY
        var count = 0
        while(head < tail) {
            val i=queue[head++]
            if(diff(pixels[i])>tolerance) continue
            accepted[i]=true
            count++
            val x = i%width
            val y = i/width
            minX = minOf(minX,x); maxX = maxOf(maxX,x)
            minY = minOf(minY,y); maxY = maxOf(maxY,y)
            fun enqueue(n:Int) {
                if(!visited[n]) {visited[n]=true;queue[tail++]=n}
            }
            if(x>(bounds?.left ?: 0)) enqueue(i-1)
            if(x<(bounds?.right ?: width-1)) enqueue(i+1)
            if(y>(bounds?.top ?: 0)) enqueue(i-width)
            if(y<(bounds?.bottom ?: height-1)) enqueue(i+width)
        }
        // Ignore tiny noise and background-scale selections.
        if(count < 16 || count > width*height*.70) return null
        val outline = ArrayList<ContourPixel>()
        for(y in minY..maxY) for(x in minX..maxX) {
            val i=y*width+x
            if(!accepted[i]) continue
            if(x==0 || y==0 || x==width-1 || y==height-1 ||
               !accepted[i-1] || !accepted[i+1] ||
               !accepted[i-width] || !accepted[i+width]) {
                if(outline.size<2500 && (x+y)%2==0) outline+=ContourPixel(x,y)
            }
        }
        return PlanarContourResult(width,height,minX,minY,maxX,maxY,count,outline)
    }
}
