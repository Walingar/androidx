/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(InternalComposeUiApi::class)

package androidx.compose.ui.test

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.TestPointerInputEventData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.nativeKeyLocation
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.SkiaRootForTest
import java.awt.Component

internal actual fun createInputDispatcher(
    testContext: TestContext,
    root: RootForTest
): InputDispatcher {
    return DesktopInputDispatcher(testContext, root as SkiaRootForTest)
}

internal class DesktopInputDispatcher(
    testContext: TestContext,
    private val root: SkiaRootForTest
) : InputDispatcher(
    testContext,
    root,
    exitHoverOnPress = false,
    moveOnScroll = false,
) {
    private val scene get() = root.scene
    private var batchedEvents = mutableListOf<() -> Unit>()

    override fun PartialGesture.enqueueDown(pointerId: Int) {
        val position = lastPositions[pointerId]!!
        val timeMillis = downTime
        enqueue {
            scene.sendPointerEvent(
                PointerEventType.Press,
                position = position,
                type = PointerType.Touch,
                timeMillis = timeMillis
            )
        }
    }
    override fun PartialGesture.enqueueMove() {
        val position = Offset(
            lastPositions.values.map { it.x }.average().toFloat(),
            lastPositions.values.map { it.y }.average().toFloat(),
        )
        val timeMillis = downTime
        enqueue {
            scene.sendPointerEvent(
                PointerEventType.Move,
                position = position,
                type = PointerType.Touch,
                timeMillis = timeMillis
            )
        }
    }

    override fun PartialGesture.enqueueMoves(
        relativeHistoricalTimes: List<Long>,
        historicalCoordinates: List<List<Offset>>
    ) {
        // TODO: add support for historical events
        enqueueMove()
    }

    override fun PartialGesture.enqueueUp(pointerId: Int) {
        val position = lastPositions[pointerId]!!
        val timeMillis = downTime
        enqueue {
            scene.sendPointerEvent(
                PointerEventType.Release,
                position = position,
                type = PointerType.Touch,
                timeMillis = timeMillis
            )
        }
    }

    override fun PartialGesture.enqueueCancel() {
        // desktop don't have cancel events as Android does
    }

    override fun MouseInputState.enqueuePress(buttonId: Int) {
        val position = lastPosition
        val timeMillis = downTime
        enqueue {
            scene.sendPointerEvent(
                PointerEventType.Press,
                position = position,
                type = PointerType.Mouse,
                timeMillis = timeMillis,
                button = PointerButton(buttonId)
            )
        }
    }

    override fun MouseInputState.enqueueMove() {
        val position = lastPosition
        val timeMillis = downTime
        enqueue {
            scene.sendPointerEvent(
                PointerEventType.Move,
                position = position,
                type = PointerType.Mouse,
                timeMillis = timeMillis
            )
        }
    }

    override fun MouseInputState.enqueueRelease(buttonId: Int) {
        val position = lastPosition
        val timeMillis = downTime
        enqueue {
            scene.sendPointerEvent(
                PointerEventType.Release,
                position = position,
                type = PointerType.Mouse,
                timeMillis = timeMillis,
                button = PointerButton(buttonId)
            )
        }
    }

    override fun MouseInputState.enqueueEnter() {
        val position = lastPosition
        val timeMillis = downTime
        enqueue {
            scene.sendPointerEvent(
                PointerEventType.Enter,
                position = position,
                type = PointerType.Mouse,
                timeMillis = timeMillis
            )
        }
    }

    override fun MouseInputState.enqueueExit() {
        val position = lastPosition
        val timeMillis = downTime
        enqueue {
            scene.sendPointerEvent(
                PointerEventType.Exit,
                position = position,
                type = PointerType.Mouse,
                timeMillis = timeMillis
            )
        }
    }

    override fun MouseInputState.enqueueCancel() {
        // desktop don't have cancel events as Android does
    }

    @OptIn(ExperimentalTestApi::class)
    override fun MouseInputState.enqueueScroll(delta: Float, scrollWheel: ScrollWheel) {
        val position = lastPosition
        val timeMillis = downTime
        enqueue {
            scene.sendPointerEvent(
                PointerEventType.Scroll,
                position = position,
                type = PointerType.Mouse,
                timeMillis = timeMillis,
                scrollDelta = if (scrollWheel == ScrollWheel.Vertical) Offset(0f, delta) else Offset(delta, 0f)
            )
        }
    }

    // TODO(b/233199964): Implement key injection for desktop
    //  don't forget to change DefaultPointerStateTracker.keyboardModifiers when we press modifiers
    override fun KeyInputState.enqueueDown(key: Key) = TODO("Not yet implemented")

    // TODO(b/233199964): Implement key injection for desktop
    override fun KeyInputState.enqueueUp(key: Key) = TODO("Not yet implemented")

    override fun RotaryInputState.enqueueRotaryScrollHorizontally(horizontalScrollPixels: Float) {
        // desktop don't have rotary events as Android Wear does
    }

    override fun RotaryInputState.enqueueRotaryScrollVertically(verticalScrollPixels: Float) {
        // desktop don't have rotary events as Android Wear does
    }

    private fun enqueue(action: () -> Unit) {
        batchedEvents.add(action)
    }

    override fun flush() {
        val copy = batchedEvents.toList()
        batchedEvents.clear()
        copy.forEach { it() }
    }

    override fun onDispose() {
        batchedEvents.clear()
    }
}
