package com.prismtest.scope

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ColorSnapshotTest {
    @Test fun chromaCopyHonorsOffsetPaddingPixelStrideAndOwnership() {
        val buffer = ByteBuffer.allocate(30)
        listOf(11,22,33).forEachIndexed { x,v -> buffer.put(3+x*2,v.toByte()) }
        listOf(44,55,66).forEachIndexed { x,v -> buffer.put(13+x*2,v.toByte()) }
        val copy = ChromaSnapshot.plane(buffer,3,10,2,3,2)
        assertArrayEquals(byteArrayOf(11,22,33,44,55,66),copy)
        buffer.put(3,99)
        assertEquals(11,copy[0].toInt())
    }
    @Test(expected = IllegalArgumentException::class) fun invalidChromaBoundsAreRejected() {
        ChromaSnapshot.plane(ByteBuffer.allocate(6),0,6,2,4,2)
    }
    @Test fun encodingUsesAndroidStandardAndRangeBits() {
        assertEquals(YuvEncoding(1,true),YuvEncoding.fromDataSpace((1 shl 16) or (1 shl 27)))
        assertEquals(YuvEncoding(2,false),YuvEncoding.fromDataSpace((6 shl 16) or (2 shl 27)))
        assertEquals(YuvEncoding(),YuvEncoding.fromDataSpace(0))
    }
    @Test fun cropToFullFrameCoordinatesAreCorrectForEveryRotation() {
        val crop=FrameCrop(20,10,180,70)
        val expected=listOf(.26f to .55f,.42f to .70f,.26f to .75f,.22f to .70f)
        listOf(0,90,180,270).forEachIndexed { index,rot ->
            val actual=EvidenceCoordinates.point(.2f,.75f,crop,200,100,rot)
            assertEquals(expected[index].first,actual.first,.00001f)
            assertEquals(expected[index].second,actual.second,.00001f)
        }
    }
    @Test fun savedAnalysisRectangleRetainsClippingInsteadOfAddingUnanalyzedPixels() {
        val bounds=EvidenceCoordinates.bounds(Roi(.5f,.5f,1f),FrameCrop(20,10,180,70),200,100,0)
        assertEquals(.35f,bounds.left,.00001f);assertEquals(.655f,bounds.right,.00001f)
        assertEquals(.1f,bounds.top,.00001f);assertEquals(.7f,bounds.bottom,.00001f)
    }
    @Test fun mappedCandidatesKeepScoresAndScaleRadii() {
        val stats=RoiStats.EMPTY.copy(dig=DigResult(42.0, spots=listOf(DigSpot(.2f,.75f,.1f,.1f,33.0,5))))
        val mapped=EvidenceCoordinates.stats(stats,FrameCrop(20,10,180,70),200,100,90)
        val point=mapped.dig.spots.single()
        assertEquals(42.0,mapped.dig.score,0.0)
        assertEquals(.42f,point.cx,.00001f);assertEquals(.7f,point.cy,.00001f)
        assertEquals(.06f,point.rx,.00001f);assertEquals(.08f,point.ry,.00001f)
        assertEquals(5,point.pixels)
    }
}
