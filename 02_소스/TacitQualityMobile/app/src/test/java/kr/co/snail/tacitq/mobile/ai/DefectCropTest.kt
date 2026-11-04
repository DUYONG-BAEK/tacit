package kr.co.snail.tacitq.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** 결함 확대 영역 — API §4.2 자르는 규칙 */
class DefectCropTest {

    @Test fun `4K 의 13px 결함은 짧은 변 20퍼센트인 432px 정사각형`() {
        val s = DefectCrop.square(listOf(2000.0, 1000.0, 2013.0, 1013.0), 3840, 2160)
        assertEquals(432, s.side)
        assertEquals(CropSquare(2006 - 216, 1006 - 216, 432), s)
    }

    @Test fun `큰 결함은 상자 긴 변의 3배`() {
        val s = DefectCrop.square(listOf(500.0, 500.0, 591.0, 572.0), 1920, 1080)   // 91×72
        assertEquals(273, s.side)
    }

    @Test fun `프레임 밖으로 나가면 안쪽으로 밀어 넣는다`() {
        assertEquals(CropSquare(0, 0, 216), DefectCrop.square(listOf(2.0, 3.0, 9.0, 10.0), 1920, 1080))
        assertEquals(CropSquare(1920 - 216, 1080 - 216, 216), DefectCrop.square(listOf(1910.0, 1070.0, 1918.0, 1078.0), 1920, 1080))
    }

    @Test fun `정사각형은 프레임 짧은 변을 넘지 않는다`() {
        assertEquals(1080, DefectCrop.square(listOf(0.0, 0.0, 900.0, 900.0), 1920, 1080).side)
    }

    @Test fun `화면 맞춤은 비율을 지키고 남는 쪽을 가운데로`() {
        val f = DefectCrop.fit(1920, 1080, 960f, 960f)                   // 정사각 화면에 16:9 영상
        assertEquals(0.5f, f.scale, 0f)
        assertEquals(0f, f.dx, 0f); assertEquals(210f, f.dy, 0f)
        assertEquals(500f, f.x(1000.0), 0f); assertEquals(710f, f.y(1000.0), 0f)
    }

    @Test fun `겹친 상자는 결함 하나로 합쳐 신뢰도 높은 순`() {
        val sq = DefectCrop.squares(listOf(
            AiBox("칩찍힘", 0.4, listOf(100.0, 100.0, 110.0, 110.0)),
            AiBox("칩찍힘", 0.7, listOf(108.0, 100.0, 118.0, 110.0)),      // 타일 경계에서 겹쳐 옴
            AiBox("이물찍힘", 0.9, listOf(1500.0, 800.0, 1510.0, 810.0)),
        ), 1920, 1080)
        assertEquals(listOf("이물찍힘", "칩찍힘"), sq.map { it.first.label })
        assertEquals(listOf(100.0, 100.0, 118.0, 110.0), sq[1].first.box)
    }
}
