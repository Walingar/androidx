package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.native.ComposeLayer
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSCoder
import platform.UIKit.UIEvent
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.UIKit.setFrame
import platform.UIKit.contentScaleFactor

// The only difference with macos' Window is that
// it has return type of UIViewController rather than unit.
fun Application(
    title: String = "JetpackNativeWindow",
    content: @Composable () -> Unit = { }

) = ComposeWindow().apply {
    setTitle(title)
    setContent(content)
} as UIViewController


@ExportObjCClass
internal actual class ComposeWindow : UIViewController {
    @OverrideInit
    actual constructor() : super(nibName = null, bundle = null)

    @OverrideInit
    constructor(coder: NSCoder) : super(coder)

    override fun touchesBegan(touches: Set<*>, withEvent: UIEvent?) {
        super.touchesBegan(touches, withEvent)
    }

    override fun touchesEnded(touches: Set<*>, withEvent: UIEvent?) {
        super.touchesEnded(touches, withEvent)
    }

    private lateinit var layer: ComposeLayer
    private lateinit var content: @Composable () -> Unit

    actual fun setTitle(title: String) {
        println("TODO: set title to SkiaWindow")
    }

    override fun viewDidLoad() {
        super.viewDidLoad()

        val (width, height) = UIScreen.mainScreen.bounds.useContents {
            this.size.width to this.size.height
        }
        layer = ComposeLayer()
        layer.setContent(content = content)

        view.contentScaleFactor = UIScreen.mainScreen.scale
        view.setFrame(CGRectMake(0.0, 0.0, width, height))
        layer.layer.attachTo(this.view)
    }

    // viewDidUnload() is deprecated and not called.
    override fun viewDidDisappear(animated: Boolean) {
        this.dispose()
    }

    actual fun setContent(
        content: @Composable () -> Unit
    ) {
        println("ComposeWindow.setContent")
        this.content = content
    }

    actual fun dispose() {
        layer.dispose()
    }
}
