package me.magnum.melonds.impl.layout

import android.content.Context
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.content.getSystemService
import me.magnum.melonds.domain.model.Rect
import me.magnum.melonds.domain.model.SCREEN_HEIGHT
import me.magnum.melonds.domain.model.SCREEN_WIDTH
import me.magnum.melonds.domain.model.consoleAspectRatio
import me.magnum.melonds.domain.model.layout.Insets
import me.magnum.melonds.domain.model.layout.LayoutComponent
import me.magnum.melonds.domain.model.layout.LayoutDisplay
import me.magnum.melonds.domain.model.layout.PositionedLayoutComponent
import me.magnum.melonds.domain.model.layout.ScreenFold
import me.magnum.melonds.domain.model.layout.ScreenLayout
import me.magnum.melonds.domain.model.layout.UILayout
import me.magnum.melonds.domain.model.layout.UILayoutVariant
import me.magnum.melonds.domain.model.ui.Orientation
import me.magnum.melonds.impl.ScreenUnitsConverter
import kotlin.math.min
import kotlin.math.roundToInt

class DefaultLayoutProvider(
    private val context: Context,
    private val screenUnitsConverter: ScreenUnitsConverter,
) {

    fun buildDefaultLayout(variant: UILayoutVariant): UILayout {
        val width = variant.uiSize.x
        val height = variant.uiSize.y
        val orientation = variant.orientation
        val folds = variant.folds
        val mainDisplay = variant.displays.mainScreenDisplay
        val secondaryDisplay = variant.displays.secondaryScreenDisplay
        val mainDisplayInsets = variant.uiInsets

        val (mainScreenLayout, secondaryScreenLayout) = if (secondaryDisplay != null) {
            val secondaryDisplayInsets = getDisplaySafeInsets(secondaryDisplay)

            // Prioritise scenarios where a secondary display is present. In this scenario, one screen is shown in each display, and they should take the maximum available
            // space, independently of orientation or folds.

            // Check if it's a dual-screen device (both displays are built-in) or it's using an external display
            if (mainDisplay.type == LayoutDisplay.Type.BUILT_IN && secondaryDisplay.type == LayoutDisplay.Type.BUILT_IN) {
                // Assume that the default display is always the top one
                if (mainDisplay.isDefaultDisplay) {
                    val secondaryDisplayOrientation = if (secondaryDisplay.width > secondaryDisplay.height) Orientation.LANDSCAPE else Orientation.PORTRAIT
                    val secondaryLayout = when (secondaryDisplayOrientation) {
                        Orientation.PORTRAIT -> buildDefaultPortraitLayout(secondaryDisplay.width, secondaryDisplay.height, secondaryDisplayInsets, LayoutComponent.BOTTOM_SCREEN)
                        Orientation.LANDSCAPE -> buildDefaultLandscapeLayout(secondaryDisplay.width, secondaryDisplay.height, secondaryDisplayInsets, LayoutComponent.BOTTOM_SCREEN)
                    }
                    buildSingleScreenLayout(width, height, LayoutComponent.TOP_SCREEN) to secondaryLayout
                } else {
                    val mainLayout = when (orientation) {
                        Orientation.PORTRAIT -> buildDefaultPortraitLayout(width, height, mainDisplayInsets, LayoutComponent.BOTTOM_SCREEN)
                        Orientation.LANDSCAPE -> buildDefaultLandscapeLayout(width, height, mainDisplayInsets, LayoutComponent.BOTTOM_SCREEN)
                    }
                    mainLayout to buildSingleScreenLayout(secondaryDisplay.width, secondaryDisplay.height, LayoutComponent.TOP_SCREEN)
                }
            } else {
                // An external display is being used. Display the bottom screen on the main display, together with all soft input components
                val mainLayout = when (orientation) {
                    Orientation.PORTRAIT -> buildDefaultPortraitLayout(width, height, mainDisplayInsets, LayoutComponent.BOTTOM_SCREEN)
                    Orientation.LANDSCAPE -> buildDefaultLandscapeLayout(width, height, mainDisplayInsets, LayoutComponent.BOTTOM_SCREEN)
                }
                mainLayout to buildSingleScreenLayout(secondaryDisplay.width, secondaryDisplay.height, LayoutComponent.TOP_SCREEN)
            }
        } else {
            val mainScreenLayout = when (orientation) {
                Orientation.PORTRAIT -> {
                    if (folds.any { it.orientation == Orientation.LANDSCAPE }) {
                        // Flip-phone layout
                        buildDefaultFoldingPortraitLayout(width, height, folds, mainDisplayInsets)
                    } else {
                        // Simple portrait layout. Ignore vertical fold since there's no good way to support it
                        buildDefaultPortraitLayout(width, height, mainDisplayInsets)
                    }
                }
                Orientation.LANDSCAPE -> {
                    if (folds.any { it.orientation == Orientation.PORTRAIT }) {
                        // Book layout
                        buildDefaultFoldingLandscapeLayout(width, height, folds, mainDisplayInsets)
                    } else if (folds.any { it.orientation == Orientation.LANDSCAPE }) {
                        // Flip-phone layout
                        buildDefaultFoldingPortraitLayout(width, height, folds, mainDisplayInsets)
                    } else {
                        // No fold
                        buildDefaultLandscapeLayout(width, height, mainDisplayInsets)
                    }
                }
            }
            mainScreenLayout to ScreenLayout()
        }

        return UILayout(mainScreenLayout, secondaryScreenLayout)
    }

    private fun buildDefaultPortraitLayout(width: Int, height: Int, insets: Insets, singleScreenComponent: LayoutComponent? = null): ScreenLayout {
        require(singleScreenComponent?.isScreen() != false) { "When specifying a single screen component, it must be a screen component" }

        val safeLeft = insets.left
        val safeTop = insets.top
        val safeRight = insets.right
        val safeBottom = insets.bottom
        val safeWidth = width - safeLeft - safeRight
        val safeHeight = height - safeTop - safeBottom

        val largeButtonsSize = screenUnitsConverter.dpToPixels(140f).toInt()
        val lrButtonsSize = screenUnitsConverter.dpToPixels(50f).toInt()
        val smallButtonsSize = screenUnitsConverter.dpToPixels(40f).toInt()
        val spacing4dp = screenUnitsConverter.dpToPixels(4f).toInt()

        var screenWidth = safeWidth
        var screenHeight = (safeWidth / consoleAspectRatio).toInt()

        val screenComponents = if (singleScreenComponent == null) {
            var screenMargin = 0
            if (screenHeight * 2 > safeHeight) {
                screenWidth = (safeHeight / 2 * consoleAspectRatio).toInt()
                screenHeight = safeHeight / 2
                screenMargin = (safeWidth - screenWidth) / 2
            }

            val topScreenView = Rect(safeLeft + screenMargin, safeTop, screenWidth, screenHeight)
            val bottomScreenView = Rect(safeLeft + screenMargin, safeTop + screenHeight, screenWidth, screenHeight)

            arrayOf(
                PositionedLayoutComponent(topScreenView, LayoutComponent.TOP_SCREEN),
                PositionedLayoutComponent(bottomScreenView, LayoutComponent.BOTTOM_SCREEN),
            )
        } else {
            // Align screen to the top of the safe area
            val screenArea = Rect(safeLeft, safeTop, screenWidth, screenHeight)
            arrayOf(PositionedLayoutComponent(screenArea, singleScreenComponent))
        }

        val screensBottom = if (singleScreenComponent == null) safeTop + screenHeight * 2 else safeTop + screenHeight
        // Check if there's space to put all the buttons below the screens. If not, place L, R, and utility buttons aligned with the top of the bottom screen
        val utilityButtonsTop = if (screensBottom + lrButtonsSize + largeButtonsSize > height) {
            safeTop + screenHeight
        } else {
            screensBottom
        }

        val dpadView = Rect(safeLeft, height - safeBottom - largeButtonsSize, largeButtonsSize, largeButtonsSize)
        val buttonsView = Rect(width - safeRight - largeButtonsSize, height - safeBottom - largeButtonsSize, largeButtonsSize, largeButtonsSize)

        return ScreenLayout(
            listOf(
                *screenComponents,
                PositionedLayoutComponent(dpadView, LayoutComponent.DPAD),
                PositionedLayoutComponent(buttonsView, LayoutComponent.BUTTONS),
                PositionedLayoutComponent(Rect(safeLeft, utilityButtonsTop, lrButtonsSize, lrButtonsSize), LayoutComponent.BUTTON_L),
                PositionedLayoutComponent(Rect(width - safeRight - lrButtonsSize, utilityButtonsTop, lrButtonsSize, lrButtonsSize), LayoutComponent.BUTTON_R),
                PositionedLayoutComponent(Rect(width / 2 - smallButtonsSize - spacing4dp / 2, height - safeBottom - smallButtonsSize, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_SELECT),
                PositionedLayoutComponent(Rect(width / 2 + spacing4dp / 2, height - safeBottom - smallButtonsSize, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_START),
                PositionedLayoutComponent(Rect(width / 2 - (smallButtonsSize * 2.0 + spacing4dp * 1.5).toInt(), utilityButtonsTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_HINGE),
                PositionedLayoutComponent(Rect(width / 2 - smallButtonsSize - (spacing4dp / 2.0).toInt(), utilityButtonsTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT),
                PositionedLayoutComponent(Rect(width / 2 + (spacing4dp / 2.0).toInt(), utilityButtonsTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_MICROPHONE_TOGGLE),
                PositionedLayoutComponent(Rect(width / 2 + smallButtonsSize + (spacing4dp * 1.5).toInt(), utilityButtonsTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE),
            )
        )
    }

    private fun buildDefaultLandscapeLayout(width: Int, height: Int, insets: Insets, singleScreenComponent: LayoutComponent? = null): ScreenLayout {
        require(singleScreenComponent?.isScreen() != false) { "When specifying a single screen component, it must be a screen component" }

        val safeLeft = insets.left
        val safeTop = insets.top
        val safeRight = insets.right
        val safeBottom = insets.bottom
        val safeWidth = width - safeLeft - safeRight
        val safeHeight = height - safeTop - safeBottom

        val largeButtonsSize = screenUnitsConverter.dpToPixels(140f).toInt()
        val lrButtonsSize = screenUnitsConverter.dpToPixels(50f).toInt()
        val smallButtonsSize = screenUnitsConverter.dpToPixels(40f).toInt()
        val spacing4dp = screenUnitsConverter.dpToPixels(4f).toInt()

        val screenComponents = if (singleScreenComponent == null) {
            var topScreenWidth = (safeWidth * 0.66f).roundToInt()
            var topScreenHeight = (topScreenWidth / consoleAspectRatio).toInt()
            if (topScreenHeight > safeHeight) {
                topScreenWidth = (safeHeight * consoleAspectRatio).toInt()
                topScreenHeight = safeHeight
            }

            val topScreenView = Rect(safeLeft, safeTop, topScreenWidth, topScreenHeight)
            val bottomScreenWidth = safeWidth - topScreenWidth
            val bottomScreenHeight = (bottomScreenWidth / consoleAspectRatio).toInt()
            val bottomScreenView = Rect(safeLeft + topScreenWidth, safeTop, bottomScreenWidth, bottomScreenHeight)

            arrayOf(
                PositionedLayoutComponent(topScreenView, LayoutComponent.TOP_SCREEN),
                PositionedLayoutComponent(bottomScreenView, LayoutComponent.BOTTOM_SCREEN),
            )
        } else {
            val screenArea = centerScreenIn(safeWidth, safeHeight)
            val offsetScreen = Rect(screenArea.x + safeLeft, screenArea.y + safeTop, screenArea.width, screenArea.height)
            arrayOf(PositionedLayoutComponent(offsetScreen, singleScreenComponent))
        }

        val dpadView = Rect(safeLeft, height - safeBottom - largeButtonsSize, largeButtonsSize, largeButtonsSize)
        val buttonsView = Rect(width - safeRight - largeButtonsSize, height - safeBottom - largeButtonsSize, largeButtonsSize, largeButtonsSize)

        return ScreenLayout(
            listOf(
                *screenComponents,
                PositionedLayoutComponent(dpadView, LayoutComponent.DPAD),
                PositionedLayoutComponent(buttonsView, LayoutComponent.BUTTONS),
                PositionedLayoutComponent(Rect(safeLeft, safeTop, lrButtonsSize, lrButtonsSize), LayoutComponent.BUTTON_L),
                PositionedLayoutComponent(Rect(width - safeRight - lrButtonsSize, safeTop, lrButtonsSize, lrButtonsSize), LayoutComponent.BUTTON_R),
                PositionedLayoutComponent(Rect((width - spacing4dp) / 2 - smallButtonsSize, height - safeBottom - smallButtonsSize, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_SELECT),
                PositionedLayoutComponent(Rect((width + spacing4dp) / 2, height - safeBottom - smallButtonsSize, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_START),
                PositionedLayoutComponent(Rect(width / 2 - (smallButtonsSize * 2.0 + spacing4dp * 1.5).toInt(), safeTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_HINGE),
                PositionedLayoutComponent(Rect(width / 2 - smallButtonsSize - (spacing4dp / 2.0).toInt(), safeTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT),
                PositionedLayoutComponent(Rect(width / 2 + (spacing4dp / 2.0).toInt(), safeTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_MICROPHONE_TOGGLE),
                PositionedLayoutComponent(Rect(width / 2 + smallButtonsSize + (spacing4dp * 1.5).toInt(), safeTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE),
            )
        )
    }

    /**
     * Creates a portrait layout that supports a horizontal fold. Screens are attached to either sides of the fold and as large as they can be to fit the screen.
     */
    private fun buildDefaultFoldingPortraitLayout(width: Int, height: Int, folds: List<ScreenFold>, insets: Insets): ScreenLayout {
        // Only one fold is supported for now
        val mainFold = folds.first()

        val safeLeft = insets.left
        val safeTop = insets.top
        val safeRight = insets.right
        val safeBottom = insets.bottom
        val safeWidth = width - safeLeft - safeRight

        val largeButtonsSize = screenUnitsConverter.dpToPixels(140f).toInt()
        val lrButtonsSize = screenUnitsConverter.dpToPixels(50f).toInt()
        val smallButtonsSize = screenUnitsConverter.dpToPixels(40f).toInt()
        val spacing4dp = screenUnitsConverter.dpToPixels(4f).toInt()

        var screenWidth = safeWidth
        var screenHeight = (safeWidth / consoleAspectRatio).toInt()
        val topHalfHeight = mainFold.foldBounds.y - safeTop
        val bottomHalfHeight = height - safeBottom - mainFold.foldBounds.bottom
        var screenMargin = 0
        if (screenHeight > topHalfHeight || screenHeight > bottomHalfHeight) {
            val limitingHeight = min(topHalfHeight, bottomHalfHeight)
            screenWidth = (limitingHeight * consoleAspectRatio).toInt()
            screenHeight = limitingHeight
            screenMargin = (safeWidth - screenWidth) / 2
        }

        val topScreenView = Rect(safeLeft + screenMargin, mainFold.foldBounds.y - screenHeight, screenWidth, screenHeight)
        val bottomScreenView = Rect(safeLeft + screenMargin, mainFold.foldBounds.bottom, screenWidth, screenHeight)
        val dpadView = Rect(safeLeft, height - safeBottom - largeButtonsSize, largeButtonsSize, largeButtonsSize)
        val buttonsView = Rect(width - safeRight - largeButtonsSize, height - safeBottom - largeButtonsSize, largeButtonsSize, largeButtonsSize)

        return ScreenLayout(
            listOf(
                PositionedLayoutComponent(topScreenView, LayoutComponent.TOP_SCREEN),
                PositionedLayoutComponent(bottomScreenView, LayoutComponent.BOTTOM_SCREEN),
                PositionedLayoutComponent(dpadView, LayoutComponent.DPAD),
                PositionedLayoutComponent(buttonsView, LayoutComponent.BUTTONS),
                PositionedLayoutComponent(Rect(safeLeft, mainFold.foldBounds.bottom, lrButtonsSize, lrButtonsSize), LayoutComponent.BUTTON_L),
                PositionedLayoutComponent(Rect(width - safeRight - lrButtonsSize, mainFold.foldBounds.bottom, lrButtonsSize, lrButtonsSize), LayoutComponent.BUTTON_R),
                PositionedLayoutComponent(Rect(width / 2 - smallButtonsSize - spacing4dp / 2, height - safeBottom - smallButtonsSize, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_SELECT),
                PositionedLayoutComponent(Rect(width / 2 + spacing4dp / 2, height - safeBottom - smallButtonsSize, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_START),
                PositionedLayoutComponent(Rect(width / 2 - (smallButtonsSize * 2.0 + spacing4dp * 1.5).toInt(), mainFold.foldBounds.bottom, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_HINGE),
                PositionedLayoutComponent(Rect(width / 2 - smallButtonsSize - (spacing4dp / 2.0).toInt(), mainFold.foldBounds.bottom, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT),
                PositionedLayoutComponent(Rect(width / 2 + spacing4dp + (spacing4dp / 2.0).toInt(), mainFold.foldBounds.bottom, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_MICROPHONE_TOGGLE),
                PositionedLayoutComponent(Rect(width / 2 + smallButtonsSize + (spacing4dp * 1.5).toInt(), mainFold.foldBounds.bottom, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE),
            )
        )
    }

    /**
     * Creates a landscape layout that supports a vertical fold. Screens are attached to either sides of the fold and as large as they can be to fit the screen.
     */
    private fun buildDefaultFoldingLandscapeLayout(width: Int, height: Int, folds: List<ScreenFold>, insets: Insets): ScreenLayout {
        // Only one fold is supported for now
        val mainFold = folds.first()

        val safeLeft = insets.left
        val safeTop = insets.top
        val safeRight = insets.right
        val safeBottom = insets.bottom
        val safeHeight = height - safeTop - safeBottom

        val largeButtonsSize = screenUnitsConverter.dpToPixels(140f).toInt()
        val lrButtonsSize = screenUnitsConverter.dpToPixels(50f).toInt()
        val smallButtonsSize = screenUnitsConverter.dpToPixels(40f).toInt()
        val spacing8dp = screenUnitsConverter.dpToPixels(8f).toInt()

        // Position to the left of the fold, attached to the fold. As big as the screen allows
        var topScreenWidth = mainFold.foldBounds.x - safeLeft
        var topScreenHeight = (topScreenWidth / consoleAspectRatio).toInt()
        if (topScreenHeight > safeHeight) {
            topScreenHeight = safeHeight
            topScreenWidth = (safeHeight * consoleAspectRatio).toInt()
        }

        // Position to the right of the fold, attached to the fold. As big as the screen allows
        var bottomScreenWidth = width - safeRight - mainFold.foldBounds.right
        var bottomScreenHeight = (bottomScreenWidth / consoleAspectRatio).toInt()
        if (bottomScreenHeight > safeHeight) {
            bottomScreenHeight = safeHeight
            bottomScreenWidth = (safeHeight * consoleAspectRatio).toInt()
        }

        // Check if the screens are small enough to be positioned under the L/R buttons
        val screenYPos = if (topScreenHeight < (safeHeight - lrButtonsSize - spacing8dp) && bottomScreenHeight < (safeHeight - lrButtonsSize - spacing8dp)) {
            safeTop + lrButtonsSize + spacing8dp
        } else {
            safeTop
        }

        val topScreenView = Rect(mainFold.foldBounds.x - topScreenWidth, screenYPos, topScreenWidth, topScreenHeight)
        val bottomScreenView = Rect(mainFold.foldBounds.right, screenYPos, bottomScreenWidth, bottomScreenHeight)
        val dpadView = Rect(safeLeft, height - safeBottom - largeButtonsSize, largeButtonsSize, largeButtonsSize)
        val buttonsView = Rect(width - safeRight - largeButtonsSize, height - safeBottom - largeButtonsSize, largeButtonsSize, largeButtonsSize)

        return ScreenLayout(
            listOf(
                PositionedLayoutComponent(topScreenView, LayoutComponent.TOP_SCREEN),
                PositionedLayoutComponent(bottomScreenView, LayoutComponent.BOTTOM_SCREEN),
                PositionedLayoutComponent(dpadView, LayoutComponent.DPAD),
                PositionedLayoutComponent(buttonsView, LayoutComponent.BUTTONS),
                PositionedLayoutComponent(Rect(safeLeft, safeTop, lrButtonsSize, lrButtonsSize), LayoutComponent.BUTTON_L),
                PositionedLayoutComponent(Rect(width - safeRight - lrButtonsSize, safeTop, lrButtonsSize, lrButtonsSize), LayoutComponent.BUTTON_R),
                PositionedLayoutComponent(Rect(mainFold.foldBounds.x - smallButtonsSize - spacing8dp, height - safeBottom - smallButtonsSize, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_SELECT),
                PositionedLayoutComponent(Rect(mainFold.foldBounds.right + spacing8dp, height - safeBottom - smallButtonsSize, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_START),
                PositionedLayoutComponent(Rect(mainFold.foldBounds.x - smallButtonsSize * 2 - spacing8dp * 2, safeTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_HINGE),
                PositionedLayoutComponent(Rect(mainFold.foldBounds.x - smallButtonsSize - spacing8dp, safeTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT),
                PositionedLayoutComponent(Rect(mainFold.foldBounds.right + smallButtonsSize + spacing8dp, safeTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_MICROPHONE_TOGGLE),
                PositionedLayoutComponent(Rect(mainFold.foldBounds.right + spacing8dp * 2, safeTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE),
            )
        )
    }

    private fun buildSingleScreenLayout(width: Int, height: Int, screenComponent: LayoutComponent): ScreenLayout {
        val screenView = centerScreenIn(width, height)
        val positionedScreenComponent = PositionedLayoutComponent(screenView, screenComponent)

        return ScreenLayout(listOf(positionedScreenComponent))
    }

    private fun centerScreenIn(width: Int, height: Int): Rect {
        val areaAspectRatio = width.toFloat() / height
        return if (areaAspectRatio > consoleAspectRatio) {
            // Center horizontally
            val scale = height.toFloat() / SCREEN_HEIGHT
            val scaledWidth = (SCREEN_WIDTH * scale).toInt()
            Rect((width - scaledWidth) / 2, 0, scaledWidth, height)
        } else {
            // Center vertically
            val scale = width.toFloat() / SCREEN_WIDTH
            val scaledHeight = (SCREEN_HEIGHT * scale).toInt()
            Rect(0, (height - scaledHeight) / 2, width, scaledHeight)
        }
    }

    private fun getDisplaySafeInsets(display: LayoutDisplay): Insets {
        if (!display.isDefaultDisplay) {
            return Insets.Zero
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Insets.Zero
        }

        val windowManager = context.getSystemService<WindowManager>() ?: return Insets.Zero

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cutoutInsets = windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.displayCutout())
            Insets(cutoutInsets.left, cutoutInsets.top, cutoutInsets.right, cutoutInsets.bottom)
        } else {
            @Suppress("DEPRECATION")
            val displayCutout = windowManager.defaultDisplay.cutout ?: return Insets.Zero
            Insets(displayCutout.safeInsetLeft, displayCutout.safeInsetTop, displayCutout.safeInsetRight, displayCutout.safeInsetBottom)
        }
    }

    fun buildPokemonLayout(variant: UILayoutVariant): UILayout {
        // The Pokemon layout is designed for a single display. Use the default layout when an external display is connected
        if (variant.displays.secondaryScreenDisplay != null) {
            return buildDefaultLayout(variant)
        }

        val width = variant.uiSize.x
        val height = variant.uiSize.y
        val orientation = variant.orientation
        val mainDisplayInsets = variant.uiInsets

        val mainScreenLayout = when (orientation) {
            Orientation.PORTRAIT -> buildPokemonPortraitLayout(width, height, mainDisplayInsets)
            Orientation.LANDSCAPE -> buildPokemonLandscapeLayout(width, height, mainDisplayInsets)
        }
        return UILayout(mainScreenLayout, ScreenLayout())
    }

    private fun buildPokemonPortraitLayout(width: Int, height: Int, insets: Insets): ScreenLayout {
        val safeLeft = insets.left
        val safeTop = insets.top
        val safeRight = insets.right
        val safeBottom = insets.bottom
        val safeWidth = width - safeLeft - safeRight
        val safeHeight = height - safeTop - safeBottom

        val baseLargeButtonsSize = screenUnitsConverter.dpToPixels(140f)
        val dpadSize = (baseLargeButtonsSize * 1.05f).toInt()
        val buttonsSize = (baseLargeButtonsSize * 0.90f).toInt()
        val lrButtonsSize = screenUnitsConverter.dpToPixels(40f).toInt()
        val smallButtonsSize = screenUnitsConverter.dpToPixels(40f).toInt()
        val spacing4dp = screenUnitsConverter.dpToPixels(4f).toInt()

        var screenWidth = safeWidth
        var screenHeight = (safeWidth / consoleAspectRatio).toInt()
        var screenMargin = 0
        if (screenHeight * 2 > safeHeight) {
            screenWidth = (safeHeight / 2 * consoleAspectRatio).toInt()
            screenHeight = safeHeight / 2
            screenMargin = (safeWidth - screenWidth) / 2
        }

        val topScreenView = Rect(safeLeft + screenMargin, safeTop, screenWidth, screenHeight)
        val bottomScreenView = Rect(safeLeft + screenMargin, safeTop + screenHeight, screenWidth, screenHeight)

        // Place the utility and shoulder buttons below the screens if there's room for them. Otherwise, place them over the top screen, since placing them over the bottom
        // screen would block touch input in that area
        val screensBottom = safeTop + screenHeight * 2
        val utilityButtonsTop = if (screensBottom + lrButtonsSize + baseLargeButtonsSize.toInt() > height) {
            safeTop
        } else {
            screensBottom
        }

        val dpadLeftOffset = screenUnitsConverter.dpToPixels(24f).toInt()
        val dpadView = Rect(safeLeft + dpadLeftOffset, height - safeBottom - dpadSize, dpadSize, dpadSize)

        val buttonsUpOffset = screenUnitsConverter.dpToPixels(20f).toInt()
        val buttonsView = Rect(width - safeRight - buttonsSize, height - safeBottom - buttonsSize - buttonsUpOffset, buttonsSize, buttonsSize)

        return ScreenLayout(
            listOf(
                PositionedLayoutComponent(topScreenView, LayoutComponent.TOP_SCREEN),
                PositionedLayoutComponent(bottomScreenView, LayoutComponent.BOTTOM_SCREEN),
                PositionedLayoutComponent(dpadView, LayoutComponent.DPAD),
                PositionedLayoutComponent(buttonsView, LayoutComponent.BUTTONS),
                PositionedLayoutComponent(Rect(safeLeft, utilityButtonsTop, lrButtonsSize, lrButtonsSize), LayoutComponent.BUTTON_L),
                PositionedLayoutComponent(Rect(width - safeRight - lrButtonsSize, utilityButtonsTop, lrButtonsSize, lrButtonsSize), LayoutComponent.BUTTON_R),
                PositionedLayoutComponent(Rect(width / 2 - smallButtonsSize - spacing4dp / 2, height - safeBottom - smallButtonsSize, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_SELECT),
                PositionedLayoutComponent(Rect(width / 2 + spacing4dp / 2, height - safeBottom - smallButtonsSize, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_START),
                PositionedLayoutComponent(Rect(width / 2 - (smallButtonsSize * 2.0 + spacing4dp * 1.5).toInt(), utilityButtonsTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_HINGE),
                PositionedLayoutComponent(Rect(width / 2 - smallButtonsSize - (spacing4dp / 2.0).toInt(), utilityButtonsTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT),
                PositionedLayoutComponent(Rect(width / 2 + (spacing4dp / 2.0).toInt(), utilityButtonsTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_MICROPHONE_TOGGLE),
                PositionedLayoutComponent(Rect(width / 2 + smallButtonsSize + (spacing4dp * 1.5).toInt(), utilityButtonsTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE),
            )
        )
    }

    private fun buildPokemonLandscapeLayout(width: Int, height: Int, insets: Insets): ScreenLayout {
        val safeLeft = insets.left
        val safeTop = insets.top
        val safeRight = insets.right
        val safeBottom = insets.bottom
        val safeWidth = width - safeLeft - safeRight
        val safeHeight = height - safeTop - safeBottom

        val baseLargeButtonsSize = screenUnitsConverter.dpToPixels(140f)
        val dpadSize = (baseLargeButtonsSize * 1.05f).toInt()
        val buttonsSize = (baseLargeButtonsSize * 0.90f).toInt()
        val lrButtonsSize = screenUnitsConverter.dpToPixels(40f).toInt()
        val smallButtonsSize = screenUnitsConverter.dpToPixels(40f).toInt()
        val spacing4dp = screenUnitsConverter.dpToPixels(4f).toInt()

        val dpadLeftOffset = screenUnitsConverter.dpToPixels(24f).toInt()
        val buttonsUpOffset = screenUnitsConverter.dpToPixels(20f).toInt()

        // The bottom (touch) screen is placed on the top right corner, as large as possible while leaving room for the face buttons below it, so that no button ever
        // covers the touch screen
        val bottomScreenMaxHeight = safeHeight - buttonsSize - buttonsUpOffset
        val bottomScreenWidth = min(safeWidth / 2, (bottomScreenMaxHeight * consoleAspectRatio).toInt()).coerceAtLeast(0)
        val bottomScreenHeight = (bottomScreenWidth / consoleAspectRatio).toInt()
        val bottomScreenView = Rect(width - safeRight - bottomScreenWidth, safeTop, bottomScreenWidth, bottomScreenHeight)

        // The top screen takes the remaining area on the left, vertically centered
        val leftAreaWidth = safeWidth - bottomScreenWidth
        val topScreenWidth = min(leftAreaWidth, (safeHeight * consoleAspectRatio).toInt())
        val topScreenHeight = (topScreenWidth / consoleAspectRatio).toInt()
        val topScreenView = Rect(safeLeft + (leftAreaWidth - topScreenWidth) / 2, safeTop + (safeHeight - topScreenHeight) / 2, topScreenWidth, topScreenHeight)

        val dpadView = Rect(safeLeft + dpadLeftOffset, height - safeBottom - dpadSize, dpadSize, dpadSize)
        val buttonsView = Rect(width - safeRight - buttonsSize, height - safeBottom - buttonsSize - buttonsUpOffset, buttonsSize, buttonsSize)

        // L and R are placed next to the D-pad and face buttons, at the bottom, so that they are reachable with the thumbs and don't cover the touch screen
        val lrButtonsTop = height - safeBottom - lrButtonsSize
        val lButtonView = Rect(dpadView.x + dpadSize + spacing4dp, lrButtonsTop, lrButtonsSize, lrButtonsSize)
        val rButtonView = Rect(buttonsView.x - spacing4dp - lrButtonsSize, lrButtonsTop, lrButtonsSize, lrButtonsSize)

        // Center the remaining buttons on the left area (over the top screen), since the right side is occupied by the touch screen
        val centerX = safeLeft + leftAreaWidth / 2

        return ScreenLayout(
            listOf(
                PositionedLayoutComponent(topScreenView, LayoutComponent.TOP_SCREEN),
                PositionedLayoutComponent(bottomScreenView, LayoutComponent.BOTTOM_SCREEN),
                PositionedLayoutComponent(dpadView, LayoutComponent.DPAD),
                PositionedLayoutComponent(buttonsView, LayoutComponent.BUTTONS),
                PositionedLayoutComponent(lButtonView, LayoutComponent.BUTTON_L),
                PositionedLayoutComponent(rButtonView, LayoutComponent.BUTTON_R),
                PositionedLayoutComponent(Rect(centerX - smallButtonsSize - spacing4dp / 2, height - safeBottom - smallButtonsSize, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_SELECT),
                PositionedLayoutComponent(Rect(centerX + spacing4dp / 2, height - safeBottom - smallButtonsSize, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_START),
                PositionedLayoutComponent(Rect(centerX - (smallButtonsSize * 2.0 + spacing4dp * 1.5).toInt(), safeTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_HINGE),
                PositionedLayoutComponent(Rect(centerX - smallButtonsSize - (spacing4dp / 2.0).toInt(), safeTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT),
                PositionedLayoutComponent(Rect(centerX + (spacing4dp / 2.0).toInt(), safeTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_MICROPHONE_TOGGLE),
                PositionedLayoutComponent(Rect(centerX + smallButtonsSize + (spacing4dp * 1.5).toInt(), safeTop, smallButtonsSize, smallButtonsSize), LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE),
            )
        )
    }
}