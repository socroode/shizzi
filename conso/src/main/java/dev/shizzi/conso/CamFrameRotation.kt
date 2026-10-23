package dev.shizzi.conso

/** Rotates raw NV21 frames before JPEG encoding for both local and remote viewers. */
internal object CamFrameRotation {
    fun frameRotation(sensorDegrees: Int, deviceDegrees: Int, front: Boolean): Int {
        require(sensorDegrees in listOf(0, 90, 180, 270))
        require(deviceDegrees in listOf(0, 90, 180, 270))
        return if (front) (sensorDegrees - deviceDegrees + 360) % 360
               else (sensorDegrees + deviceDegrees) % 360
    }
    fun rotateNV21(source: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(degrees in listOf(0, 90, 180, 270))
        require(source.size == width * height * 3 / 2)
        if (degrees == 0) return source
        val result = ByteArray(source.size)
        val outputWidth = if (degrees == 90 || degrees == 270) height else width
        val outputHeight = if (degrees == 90 || degrees == 270) width else height
        fun index(x:Int,y:Int,w:Int,h:Int):Int=when(degrees){
            90 -> x*h+h-1-y
            180 -> (h-1-y)*w+w-1-x
            270 -> (w-1-x)*h+y
            else -> y*w+x
        }
        for (y in 0 until height) for (x in 0 until width) {
            val target = index(x,y,width,height)
            result[target] = source[y * width + x]
        }
        val start = width * height
        for (y in 0 until height / 2) for (x in 0 until width / 2) {
            val target = index(x,y,width/2,height/2)
            val original = start + y * width + x * 2
            val rotated = start + target * 2
            result[rotated] = source[original]
            result[rotated + 1] = source[original + 1]
        }
        check(result.size == outputWidth * outputHeight * 3 / 2)
        return result
    }
    /** Fit with narrow black borders when alignment/aspect ratios differ; never stretch. */
    fun fitNV21(source:ByteArray,width:Int,height:Int,outWidth:Int,outHeight:Int):ByteArray{
        require(width%2==0&&height%2==0&&outWidth%2==0&&outHeight%2==0)
        require(source.size==width*height*3/2)
        if(width==outWidth&&height==outHeight)return source
        val scale=minOf(outWidth.toDouble()/width,outHeight.toDouble()/height)
        val w=(width*scale).toInt()/2*2
        val h=(height*scale).toInt()/2*2
        val left=(outWidth-w)/4*2;val top=(outHeight-h)/4*2
        val result=ByteArray(outWidth*outHeight*3/2){if(it<outWidth*outHeight)16 else 128.toByte()}
        for(y in 0 until h)for(x in 0 until w){
            result[(y+top)*outWidth+x+left]=source[(y*height/h)*width+x*width/w]
        }
        val a=width*height;val b=outWidth*outHeight
        for(y in 0 until h/2)for(x in 0 until w/2){
            val from=a+(y*(height/2)/(h/2))*width+(x*(width/2)/(w/2))*2
            val to=b+(y+top/2)*outWidth+(x+left/2)*2
            result[to]=source[from];result[to+1]=source[from+1]
        }
        return result
    }

}
