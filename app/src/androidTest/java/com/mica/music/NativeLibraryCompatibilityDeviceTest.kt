package com.mica.music

import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.graphics.path.PathIterator
import androidx.graphics.path.PathSegment
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLibraryCompatibilityDeviceTest {
    @Test
    fun packagedLibrariesLoadAndPathIterationPreservesGeometry() {
        Log.i("NativeCompatibility", "sdk=${Build.VERSION.SDK_INT} pageSize=${Os.sysconf(OsConstants._SC_PAGESIZE)}")
        listOf(
            "c++_shared", "androidx.graphics.path", "taglib", "usb-1.0",
            "sylvakru_usb_exclusive", "ffmpegJNI",
        ).forEach(System::loadLibrary)

        val path = Path().apply {
            moveTo(2f, 3f)
            lineTo(8f, 9f)
            quadTo(14f, 15f, 20f, 21f)
            cubicTo(22f, 23f, 24f, 25f, 26f, 27f)
            close()
        }
        // API < 34 exercises Graphics Path's JNI iterator; newer devices use the platform iterator.
        val segments = PathIterator(path).asSequence().toList()
        assertEquals(
            listOf(PathSegment.Type.Move, PathSegment.Type.Line, PathSegment.Type.Quadratic,
                PathSegment.Type.Cubic, PathSegment.Type.Close),
            segments.map { it.type },
        )
        assertEquals(PointF(2f, 3f), segments[0].points.single())
        assertEquals(PointF(8f, 9f), segments[1].points.last())
        assertEquals(PointF(20f, 21f), segments[2].points.last())
        assertEquals(PointF(26f, 27f), segments[3].points.last())

        // Ellipse conics also exercise the native conic-to-quadratic conversion on API 34+.
        val ellipse = Path().apply { addOval(0f, 0f, 16f, 8f, Path.Direction.CW) }
        val curves = PathIterator(ellipse).asSequence().toList()
        assertTrue(curves.any { it.type == PathSegment.Type.Quadratic })
        assertTrue(curves.none { it.type == PathSegment.Type.Conic })
        assertTrue(curves.flatMap { it.points.asList() }.all { it.x.isFinite() && it.y.isFinite() })
    }
}
