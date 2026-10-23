package dev.shizzi.conso

import org.junit.Assert.*
import org.junit.Test

class CamFrameRotationTest {
    private val frame = byteArrayOf(0,1,2,3,4,5,6,7,10,11,20,21)
    private fun bytes(expected: IntArray) = expected.map { it.toByte() }.toByteArray()

    @Test fun rotatesPixelsAndVUBy90Degrees() {
        assertArrayEquals(bytes(intArrayOf(4,0,5,1,6,2,7,3,10,11,20,21)),
            CamFrameRotation.rotateNV21(frame,4,2,90))
    }
    @Test fun fixesUpsideDown180AndPreservesVUOrder() {
        assertArrayEquals(bytes(intArrayOf(7,6,5,4,3,2,1,0,20,21,10,11)),
            CamFrameRotation.rotateNV21(frame,4,2,180))
    }
    @Test fun rotatesBy270Degrees() {
        assertArrayEquals(bytes(intArrayOf(3,7,2,6,1,5,0,4,10,11,20,21)),
            CamFrameRotation.rotateNV21(frame,4,2,270))
    }
    @Test fun uprightBufferUnchanged() {
        assertArrayEquals(frame,CamFrameRotation.rotateNV21(frame,4,2,0))
    }
    @Test fun respectsSensorAndDeviceOrientationsForBothLenses() {
        assertEquals(90,CamFrameRotation.frameRotation(90,0,false))
        assertEquals(180,CamFrameRotation.frameRotation(90,90,false))
        assertEquals(270,CamFrameRotation.frameRotation(90,180,false))
        assertEquals(0,CamFrameRotation.frameRotation(90,270,false))
        assertEquals(270,CamFrameRotation.frameRotation(270,0,true))
        assertEquals(180,CamFrameRotation.frameRotation(270,90,true))
        assertEquals(90,CamFrameRotation.frameRotation(270,180,true))
    }
    @Test fun rejectsInvalidRotationOrDimensions() {
        assertThrows(IllegalArgumentException::class.java) { CamFrameRotation.rotateNV21(frame,3,2,90) }
        assertThrows(IllegalArgumentException::class.java) { CamFrameRotation.rotateNV21(frame,4,2,45) }
    }
}
