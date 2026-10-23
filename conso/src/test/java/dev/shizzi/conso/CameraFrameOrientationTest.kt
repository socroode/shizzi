package dev.shizzi.conso

import org.junit.Assert.*
import org.junit.Test

class CameraFrameOrientationTest {
    @Test fun backCameraOrientationFollowsPhysicalDeviceRotation() {
        assertEquals(90, CameraFrameOrientation.jpegRotation(90, 0, false))
        assertEquals(180, CameraFrameOrientation.jpegRotation(90, 90, false))
        assertEquals(270, CameraFrameOrientation.jpegRotation(90, 180, false))
        assertEquals(0, CameraFrameOrientation.jpegRotation(90, 270, false))
    }

    @Test fun selfieCameraUsesReverseSensorCompensation() {
        assertEquals(270, CameraFrameOrientation.jpegRotation(270, 0, true))
        assertEquals(180, CameraFrameOrientation.jpegRotation(270, 90, true))
        assertEquals(90, CameraFrameOrientation.jpegRotation(270, 180, true))
        assertEquals(0, CameraFrameOrientation.jpegRotation(270, 270, true))
    }

    @Test fun orientationJitterAndFlatPhoneKeepStableImage() {
        assertEquals(0, CameraFrameOrientation.snappedDeviceOrientation(-1, 0))
        assertEquals(0, CameraFrameOrientation.snappedDeviceOrientation(50, 0))
        assertEquals(90, CameraFrameOrientation.snappedDeviceOrientation(60, 0))
        assertEquals(90, CameraFrameOrientation.snappedDeviceOrientation(50, 90))
        assertEquals(180, CameraFrameOrientation.snappedDeviceOrientation(180, 90))
        assertEquals(270, CameraFrameOrientation.snappedDeviceOrientation(270, 180))
        assertEquals(0, CameraFrameOrientation.snappedDeviceOrientation(359, 270))
    }

    private val nv21 = byteArrayOf(
        0, 1, 2, 3,
        4, 5, 6, 7,
        10, 11, 20, 21
    )

    @Test fun portraitClockwise90RotatesYAndVuWithoutMirror() {
        val out = CameraFrameOrientation.rotateNv21(nv21, 4, 2, 90)
        assertEquals(2, out.width)
        assertEquals(4, out.height)
        assertArrayEquals(byteArrayOf(4, 0, 5, 1, 6, 2, 7, 3, 10, 11, 20, 21), out.pixels)
    }

    @Test fun upsideDown180RestoresUprightWithCorrectChroma() {
        val out = CameraFrameOrientation.rotateNv21(nv21, 4, 2, 180)
        assertEquals(4, out.width)
        assertEquals(2, out.height)
        assertArrayEquals(byteArrayOf(7, 6, 5, 4, 3, 2, 1, 0, 20, 21, 10, 11), out.pixels)
    }

    @Test fun landscape270RotatesCorrectly() {
        val out = CameraFrameOrientation.rotateNv21(nv21, 4, 2, 270)
        assertEquals(2, out.width)
        assertEquals(4, out.height)
        assertArrayEquals(byteArrayOf(3, 7, 2, 6, 1, 5, 0, 4, 20, 21, 10, 11), out.pixels)
    }

    @Test fun noRotationPreservesOriginalPixelBytes() {
        val out = CameraFrameOrientation.rotateNv21(nv21, 4, 2, 0)
        assertSame(nv21, out.pixels)
        assertEquals(4, out.width)
        assertEquals(2, out.height)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonCardinalAngle() {
        CameraFrameOrientation.rotateNv21(nv21, 4, 2, 45)
    }
}
