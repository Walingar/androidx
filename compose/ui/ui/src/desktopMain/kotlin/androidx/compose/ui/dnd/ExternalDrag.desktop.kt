/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.compose.ui.dnd

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.LocalWindow
import java.awt.Point
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.dnd.DropTargetListener
import java.io.File

@Composable
fun Modifier.onExternalFileDrag(
    enabled: Boolean = true,
    onDragStart: () -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrop: (List<File>) -> Unit = {},
): Modifier = composed {
    if (!enabled) {
        return@composed Modifier
    }
    val window = LocalWindow.current ?: return@composed Modifier

    var componentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val boundsInWindow = componentCoordinates?.boundsInWindow()

    var componentDragHandleId by remember { mutableStateOf<Int?>(null) }

    // components drag handlers depend on the component position,
    // so we should reinstall handlers with a new component position when it was changed
    DisposableEffect(window, boundsInWindow) {
        val currentComponentCoordinates = componentCoordinates ?: return@DisposableEffect onDispose { }
        when (val currentDropTarget = window.dropTarget) {
            is AwtWindowDropTarget -> {
                // if our drop target is already assigned simply add new drag handler for the current component
                componentDragHandleId = currentDropTarget.installComponentDragHandler(
                    currentComponentCoordinates, onDragStart, onDrag, onDragCancel, onDrop
                )
            }

            null -> {
                // drop target is not installed for the window, so assign it and add new drag handler for the current component
                val newDropTarget = AwtWindowDropTarget(window)
                componentDragHandleId = newDropTarget.installComponentDragHandler(
                    currentComponentCoordinates, onDragStart, onDrag, onDragCancel, onDrop
                )
                window.dropTarget = newDropTarget
            }

            else -> {
                error("Window already has unknown external dnd handler, cannot attach onExternalFileDrag")
            }
        }

        onDispose {
            val dropTarget = window.dropTarget
            if (dropTarget is AwtWindowDropTarget) {
                val handleIdToRemove = componentDragHandleId
                if (handleIdToRemove != null) {
                    dropTarget.stopDragHandling(handleIdToRemove)
                }
            }
        }
    }

    Modifier
        .onGloballyPositioned {
            componentCoordinates = it
        }
}

/**
 * Provides a way to subscribe on external drag for given [window] using [installComponentDragHandler]
 *
 * [Window] allows having only one [DropTarget], so this is the main [DropTarget] that handles all the drag subscriptions
 */
private class AwtWindowDropTarget(
    private val window: Window
) : DropTarget(window, DnDConstants.ACTION_MOVE, null, true) {
    private var idsCounter = 0

    // all components that are subscribed to external drag and drop for the window
    private val handlers = mutableMapOf<Int, ComponentDragHandler>()

    // drag coordinates used to detect that drag entered/exited components
    private var windowDragCoordinates: Offset? = null

    init {
        addDropTargetListener(
            AwtWindowDragTargetListener(
                // notify components on window border that drag is started.
                onDragEnterWindow = { awtPoint ->
                    val newWindowDragCoordinates = awtPoint.windowOffset()
                    for ((_, handler) in handlers) {
                        val isInside =
                            isExternalDragInsideComponent(handler.componentCoordinates, newWindowDragCoordinates)
                        if (isInside) {
                            handler.onDragStart()
                        }
                    }
                    windowDragCoordinates = newWindowDragCoordinates
                },
                // drag moved inside window, we should calculate whether drag entered/exited components or just moved inside them
                onDragInsideWindow = { awtPoint ->
                    val newWindowDragCoordinates = awtPoint.windowOffset()
                    for ((_, handler) in handlers) {
                        val componentCoordinates = handler.componentCoordinates
                        val oldDragCoordinates = windowDragCoordinates

                        val wasDragInside = isExternalDragInsideComponent(componentCoordinates, oldDragCoordinates)
                        val newIsDragInside =
                            isExternalDragInsideComponent(componentCoordinates, newWindowDragCoordinates)

                        if (!wasDragInside && newIsDragInside) {
                            handler.onDragStart()
                        }

                        if (wasDragInside && !newIsDragInside) {
                            handler.onDragCancel()
                        }

                        if (newIsDragInside) {
                            handler.onDrag(componentCoordinates.windowToLocal(newWindowDragCoordinates))
                        }
                    }
                    windowDragCoordinates = newWindowDragCoordinates
                },
                // notify components on window border drag exited window
                onDragExit = {
                    for ((_, handler) in handlers) {
                        val componentCoordinates = handler.componentCoordinates
                        val oldDragCoordinates = windowDragCoordinates
                        val wasDragInside = isExternalDragInsideComponent(componentCoordinates, oldDragCoordinates)
                        if (wasDragInside) {
                            handler.onDragCancel()
                        }
                    }
                    windowDragCoordinates = null
                },
                // notify all components under the pointer that drop happened
                onDrop = {
                    var anyDrops = false
                    for ((_, handler) in handlers) {
                        if (isExternalDragInsideComponent(handler.componentCoordinates, windowDragCoordinates)) {
                            handler.onDrop(it)
                            anyDrops = true
                        }
                    }
                    windowDragCoordinates = null
                    // tell swing whether some components accepted the drop
                    return@AwtWindowDragTargetListener anyDrops
                }
            )
        )
    }

    override fun setActive(isActive: Boolean) {
        super.setActive(isActive)
        if (!isActive) {
            windowDragCoordinates = null
        }
    }

    /**
     * Subscribes on drag events for [window].
     * If drag is going and component is under pointer [onDragStart] can be called synchronously.
     *
     * @param componentCoordinates coordinates of the component used to properly detect when drag entered/exited component
     * @return handler id that can be used later to remove subscription using [stopDragHandling]
     */
    fun installComponentDragHandler(
        componentCoordinates: LayoutCoordinates,
        onDragStart: () -> Unit,
        onDrag: (Offset) -> Unit,
        onDragCancel: () -> Unit,
        onDrop: (List<File>) -> Unit
    ): Int {
        isActive = true

        handlers[idsCounter] = ComponentDragHandler(componentCoordinates, onDragStart, onDrag, onDragCancel, onDrop)

        if (isExternalDragInsideComponent(componentCoordinates, windowDragCoordinates)) {
            onDragStart()
        }
        return idsCounter++
    }

    /**
     * Unsubscribes handler with [handleId].
     * Calls [ComponentDragHandler.onDragCancel] if drag is going and handler's component is under pointer
     *
     * Disable drag handling for [window] if there are no more handlers.
     *
     * @param handleId id provided by [installComponentDragHandler] function
     */
    fun stopDragHandling(handleId: Int) {
        val handler = handlers[handleId]
        if (handler != null && isExternalDragInsideComponent(handler.componentCoordinates, windowDragCoordinates)) {
            handler.onDragCancel()
        }
        handlers.remove(handleId)

        if (handlers.isEmpty()) {
            isActive = false
        }
    }

    private fun Point.windowOffset(): Offset {
        val transform = window.graphicsConfiguration.defaultTransform
        val offsetX = (x - window.insets.left) * transform.scaleX.toFloat()
        val offsetY = (y - window.insets.top) * transform.scaleY.toFloat()

        return Offset(offsetX, offsetY)
    }


    private class ComponentDragHandler(
        val componentCoordinates: LayoutCoordinates,
        val onDragStart: () -> Unit,
        val onDrag: (Offset) -> Unit,
        val onDragCancel: () -> Unit,
        val onDrop: (List<File>) -> Unit
    )

    companion object {
        private fun isExternalDragInsideComponent(
            componentCoordinates: LayoutCoordinates?,
            windowDragCoordinates: Offset?
        ): Boolean {
            if (componentCoordinates == null || windowDragCoordinates == null) {
                return false
            }

            return componentCoordinates.boundsInWindow().contains(windowDragCoordinates)
        }
    }
}

private class AwtWindowDragTargetListener(
    private val onDragEnterWindow: (Point) -> Unit,
    private val onDragInsideWindow: (Point) -> Unit,
    private val onDragExit: () -> Unit,
    private val onDrop: (List<File>) -> Boolean,
) : DropTargetListener {
    override fun dragEnter(dtde: DropTargetDragEvent) {
        onDragEnterWindow(dtde.location)
    }

    override fun dragOver(dtde: DropTargetDragEvent) {
        onDragInsideWindow(dtde.location)
    }

    override fun dropActionChanged(dtde: DropTargetDragEvent) {
        // Should we notify about it?
    }

    override fun dragExit(dte: DropTargetEvent) {
        onDragExit()
    }

    override fun drop(dtde: DropTargetDropEvent) {
        dtde.acceptDrop(dtde.dropAction)

        val transferable = dtde.transferable
        try {
            val data = (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>) ?: run {
                onDragExit()
                dtde.dropComplete(false)
                return
            }
            onDrop(data.filterIsInstance<File>())
            dtde.dropComplete(true)
            return
        } catch (e: Exception) {
            onDragExit()
            dtde.dropComplete(false)
        }
    }
}