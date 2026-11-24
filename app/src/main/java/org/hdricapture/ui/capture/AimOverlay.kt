// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import org.hdricapture.geom.Quat
import org.hdricapture.plan.AimSpot
import org.hdricapture.plan.SpotProjector
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

private val Done = Color(0xFF4CAF50)
private val Target = Color(0xFFFFC107)
private val Pending = Color.White.copy(alpha = 0.8f)

/** Draws aim spots projected into the live preview, a crosshair, and an arrow toward an off-screen target. */
@Composable
fun AimOverlay(
    pose: Quat,
    spots: List<AimSpot>,
    done: Set<Int>,
    targetIndex: Int?,
    headingDeg: Double,
    hFovDeg: Double,
    holdProgress: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val projector = SpotProjector(size.width, size.height, hFovDeg)
        val r = size.minDimension * 0.06f
        val centre = Offset(size.width / 2, size.height / 2)

        for (spot in spots) {
            val p = projector.project(pose, spot.direction(headingDeg))
            if (!p.inFront) continue
            val o = Offset(p.x, p.y)
            if (o.x < -r || o.y < -r || o.x > size.width + r || o.y > size.height + r) continue
            when {
                spot.index in done -> drawCircle(Done.copy(alpha = 0.6f), r * 0.6f, o)
                spot.index == targetIndex -> {
                    drawCircle(Target, r, o, style = Stroke(r * 0.15f))
                    if (holdProgress > 0f) {
                        drawArc(Target, -90f, 360f * holdProgress, false, Offset(o.x - r * 1.3f, o.y - r * 1.3f),
                            Size(r * 2.6f, r * 2.6f), style = Stroke(r * 0.2f))
                    }
                }
                else -> drawCircle(Pending, r * 0.7f, o, style = Stroke(r * 0.08f))
            }
        }

        // Crosshair.
        val c = r * 0.5f
        drawLine(Color.White, Offset(centre.x - c, centre.y), Offset(centre.x + c, centre.y), strokeWidth = 3f)
        drawLine(Color.White, Offset(centre.x, centre.y - c), Offset(centre.x, centre.y + c), strokeWidth = 3f)

        // Arrow toward the target when it is not comfortably on screen.
        val target = spots.getOrNull(targetIndex ?: -1) ?: return@Canvas
        val tp = projector.project(pose, target.direction(headingDeg))
        val margin = r * 2
        val onScreen = tp.inFront && tp.x in margin..(size.width - margin) && tp.y in margin..(size.height - margin)
        if (!onScreen) drawArrow(centre, Offset(tp.x, tp.y), size.minDimension * 0.3f, r)

        // Level indicator: horizon tilt as a short line through the centre.
        val roll = SpotProjector.rollDeg(pose).toFloat()
        if (!target.isPole && abs(roll) > 2f) {
            rotate(-roll, centre) {
                drawLine(Target.copy(alpha = 0.8f), Offset(centre.x - r * 3, centre.y), Offset(centre.x + r * 3, centre.y), strokeWidth = 4f)
            }
        }
    }
}

private fun DrawScope.drawArrow(from: Offset, toward: Offset, distance: Float, size: Float) {
    val angle = Math.toDegrees(atan2((toward.y - from.y).toDouble(), (toward.x - from.x).toDouble())).toFloat()
    val d = min(distance, (toward - from).getDistance())
    rotate(angle, from) {
        val tip = Offset(from.x + d, from.y)
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - size * 1.4f, tip.y - size * 0.8f)
            lineTo(tip.x - size * 1.4f, tip.y + size * 0.8f)
            close()
        }
        drawPath(path, Target)
    }
}

/** Equirectangular mini-map of captured / pending spots. */
@Composable
fun CoverageMap(spots: List<AimSpot>, done: Set<Int>, targetIndex: Int?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Color.Black.copy(alpha = 0.5f))
        drawRect(Color.White.copy(alpha = 0.6f), style = Stroke(2f))
        drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        for (s in spots) {
            val x = (((s.yawDeg + 180) % 360) / 360 * size.width).toFloat()
            val y = ((90 - s.pitchDeg) / 180 * size.height).toFloat()
            val color = when {
                s.index in done -> Done
                s.index == targetIndex -> Target
                else -> Color.White.copy(alpha = 0.5f)
            }
            drawCircle(color, size.height * 0.05f, Offset(x, y))
        }
    }
}
