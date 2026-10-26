package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.data.ReplacementRule
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class InspectableItem(
    val text: String,
    val bounds: Rect,
    val packageName: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val textSizeSp: Float = 16f,
    val hasRealText: Boolean = true,
    val isLeaf: Boolean = true,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val fullSentence: String? = null,
    val fontWeight: Int = 700
)

@SuppressLint("ViewConstructor")
class InspectionOverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val inspectableItems: List<InspectableItem>,
    private val screenshotBitmap: Bitmap?,
    private val onDismiss: () -> Unit,
    private val onRuleConfirmed: (ReplacementRule) -> Unit
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var isDraggingRect = false
    private val manualDragRect = Rect()

    private val scrimPaint = Paint().apply {
        color = Color.argb(45, 15, 23, 42) // subtle dark scrim
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0284C7.toInt() // sky blue
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(25, 2, 132, 199)
        style = Paint.Style.FILL
    }

    private val manualRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF59E0B.toInt() // amber for manual selection
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private val textBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        isFakeBoldText = true
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0284C7.toInt()
        style = Paint.Style.FILL
    }

    private var activeDialogView: View? = null

    val layoutParamsWindow: WindowManager.LayoutParams

    init {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParamsWindow = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        setWillNotDraw(false)
        setupTopBanner()
    }

    private fun setupTopBanner() {
        // Floating dismiss button (X) that cleanly closes the inspection overlay
        val closeBtn = FrameLayout(context).apply {
            val btnSize = (48 * density).roundToInt()
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.TOP or Gravity.END
                val marginHoriz = (18 * density).roundToInt()
                val marginTop = (48 * density).roundToInt()
                setMargins(0, marginTop, marginHoriz, 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE0F172A.toInt()) // Dark slate
                setStroke((1.5f * density).roundToInt(), 0xFF38BDF8.toInt()) // Cyan border
            }
            elevation = 16f * density

            val icon = TextView(context).apply {
                text = "✕"
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }
            addView(icon)
            setOnClickListener {
                dismissSelf()
            }
        }
        addView(closeBtn)

        if (inspectableItems.isEmpty()) {
            val emptyBadge = TextView(context).apply {
                text = "No dates found (format: 2026-10-23 09:45)"
                setTextColor(Color.WHITE)
                textSize = 12f
                val padH = (16 * density).roundToInt()
                val padV = (8 * density).roundToInt()
                setPadding(padH, padV, padH, padV)
                background = GradientDrawable().apply {
                    setColor(0xEE0F172A.toInt())
                    cornerRadius = 16f * density
                    setStroke((1f * density).roundToInt(), 0xFF475569.toInt())
                }
                val lpEmpty = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = (32 * density).roundToInt()
                }
                layoutParams = lpEmpty
            }
            addView(emptyBadge)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // Draw bounding boxes around inspectable nodes
        val cornerRadius = 6f * density
        for ((index, item) in inspectableItems.withIndex()) {
            val rectF = RectF(item.bounds)
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, fillPaint)
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, strokePaint)

            // Draw a small numbered badge for readability
            val badgeText = (index + 1).toString()
            val badgeW = textBadgePaint.measureText(badgeText) + 12f * density
            val badgeH = 18f * density
            val badgeRect = RectF(
                rectF.left,
                (rectF.top - badgeH).coerceAtLeast(0f),
                rectF.left + badgeW,
                rectF.top.coerceAtLeast(badgeH)
            )
            canvas.drawRoundRect(badgeRect, 4f * density, 4f * density, badgeBgPaint)
            canvas.drawText(
                badgeText,
                badgeRect.left + 6f * density,
                badgeRect.bottom - 4f * density,
                textBadgePaint
            )
        }

        // Draw manual drag selection box if active
        if (isDraggingRect && !manualDragRect.isEmpty) {
            val manualRectF = RectF(manualDragRect)
            canvas.drawRoundRect(manualRectF, cornerRadius, cornerRadius, manualRectPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (activeDialogView != null) {
            return super.onTouchEvent(event)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isDraggingRect = false
                manualDragRect.setEmpty()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dx > touchSlop * 2 || dy > touchSlop * 2) {
                    isDraggingRect = true
                    manualDragRect.set(
                        min(downX, event.x).roundToInt(),
                        min(downY, event.y).roundToInt(),
                        max(downX, event.x).roundToInt(),
                        max(downY, event.y).roundToInt()
                    )
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDraggingRect) {
                    // Tap event -> find the clicked item
                    val touchX = event.x.roundToInt()
                    val touchY = event.y.roundToInt()
                    val clickedItem = findItemAt(touchX, touchY)

                    if (clickedItem != null) {
                        openReplacementDialog(clickedItem)
                    } else {
                        // Tapped outside any word on the background scrim -> close overlay
                        dismissSelf()
                    }
                } else {
                    // Dragged area completed -> fallback manual area selection
                    if (manualDragRect.width() > 30 && manualDragRect.height() > 15) {
                        val manualItem = InspectableItem(
                            text = "",
                            bounds = Rect(manualDragRect),
                            packageName = null,
                            viewId = null,
                            className = null
                        )
                        openReplacementDialog(manualItem)
                    }
                    isDraggingRect = false
                    manualDragRect.setEmpty()
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findItemAt(x: Int, y: Int): InspectableItem? {
        // Return smallest bounding box containing (x,y) to handle nested items cleanly
        return inspectableItems
            .filter { it.bounds.contains(x, y) }
            .minByOrNull { it.bounds.width() * it.bounds.height() }
    }

    private fun openReplacementDialog(item: InspectableItem) {
        // Run advanced optical and typography analysis on the screenshot
        val analysis = TextVisualAnalyzer.analyze(screenshotBitmap, item.bounds)
        var selectedBgColor = analysis.backgroundColor
        var selectedTextColor = analysis.textColor
        var selectedFontWeight = if (item.fontWeight != 700 && item.fontWeight != 400) item.fontWeight else analysis.fontWeight
        val isPartOfSentence = !item.fullSentence.isNullOrBlank() && item.fullSentence.trim() != item.text.trim()
        val selectedMatchMode = if (isPartOfSentence) ReplacementRule.MATCH_CONTAINS else ReplacementRule.MATCH_EXACT

        val dialogContainer = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).roundToInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(0xFF1E293B.toInt()) // slate 800 M3 surface
                cornerRadius = 24f * density
                setStroke((1.5f * density).roundToInt(), 0xFF334155.toInt())
            }
            elevation = 24f * density
        }

        // Title
        val titleText = TextView(context).apply {
            text = "Replace Date / Timestamp"
            textSize = 20f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
        }
        card.addView(titleText)

        // Subtitle / context
        val subText = TextView(context).apply {
            text = if (!item.fullSentence.isNullOrBlank() && item.fullSentence != item.text) {
                "In: \"${item.fullSentence}\""
            } else {
                "Detected: \"${item.text}\""
            }
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, (4 * density).roundToInt(), 0, (10 * density).roundToInt())
        }
        card.addView(subText)

        // Original Date Label & Input
        val origLabel = TextView(context).apply {
            text = "ORIGINAL DATE"
            textSize = 11f
            setTextColor(0xFF38BDF8.toInt())
            paint.isFakeBoldText = true
        }
        card.addView(origLabel)

        val origEditText = EditText(context).apply {
            setText(item.text)
            setTextColor(Color.WHITE)
            textSize = 15f
            background = createInputBackground()
            val padInput = (10 * density).roundToInt()
            setPadding(padInput, padInput, padInput, padInput)
        }
        card.addView(origEditText)

        // Replacement Date Label & Input
        val repLabel = TextView(context).apply {
            text = "NEW DATE"
            textSize = 11f
            setTextColor(0xFF34D399.toInt())
            paint.isFakeBoldText = true
            setPadding(0, (12 * density).roundToInt(), 0, 0)
        }
        card.addView(repLabel)

        val repEditText = EditText(context).apply {
            hint = "e.g. 2026-10-25 10:30"
            setHintTextColor(0xFF64748B.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            background = createInputBackground()
            val padInput = (10 * density).roundToInt()
            setPadding(padInput, padInput, padInput, padInput)
            requestFocus()
        }
        card.addView(repEditText)

        // Quick Date Adjuster Action Chips
        val quickDateScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, (8 * density).roundToInt(), 0, (4 * density).roundToInt())
        }
        val quickDateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val quickActions = listOf(
            "Now" to { DateDetector.getCurrentDateTimeString() },
            "+1 Hour" to { DateDetector.adjustDate(repEditText.text.toString().ifBlank { origEditText.text.toString() }, java.util.Calendar.HOUR_OF_DAY, 1) },
            "+1 Day" to { DateDetector.adjustDate(repEditText.text.toString().ifBlank { origEditText.text.toString() }, java.util.Calendar.DAY_OF_YEAR, 1) },
            "+1 Year" to { DateDetector.adjustDate(repEditText.text.toString().ifBlank { origEditText.text.toString() }, java.util.Calendar.YEAR, 1) }
        )

        for ((actionLabel, actionLambda) in quickActions) {
            val chip = Button(context).apply {
                text = actionLabel
                textSize = 11f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(0xFF0284C7.toInt())
                    cornerRadius = 10f * density
                }
                val padChip = (6 * density).roundToInt()
                setPadding(padChip, padChip, padChip, padChip)
                val lpChip = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (32 * density).roundToInt()
                ).apply {
                    marginEnd = (6 * density).roundToInt()
                }
                layoutParams = lpChip
                setOnClickListener {
                    val resultDate = actionLambda()
                    repEditText.setText(resultDate)
                    repEditText.setSelection(resultDate.length)
                }
            }
            quickDateRow.addView(chip)
        }
        quickDateScroll.addView(quickDateRow)
        card.addView(quickDateScroll)

        // Live Preview Box
        val previewLabel = TextView(context).apply {
            text = "VISUAL PREVIEW"
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
            paint.isFakeBoldText = true
            setPadding(0, (10 * density).roundToInt(), 0, (4 * density).roundToInt())
        }
        card.addView(previewLabel)

        val targetTextSize = item.textSizeSp.coerceIn(11f, 36f)
        var selectedWidthScale = 1.0f

        val previewBox = TextView(context).apply {
            text = if (item.text.isNotBlank()) item.text else "Preview"
            setTextColor(selectedTextColor)
            textSize = targetTextSize
            gravity = Gravity.CENTER
            val padP = (12 * density).roundToInt()
            setPadding(padP, padP, padP, padP)
            background = GradientDrawable().apply {
                setColor(selectedBgColor)
                cornerRadius = 8f * density
                setStroke((1f * density).roundToInt(), 0xFF475569.toInt())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, selectedFontWeight, false)
            } else {
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (selectedFontWeight >= 600) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            paint.isFakeBoldText = (selectedFontWeight >= 600)
        }
        card.addView(previewBox)

        repEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                previewBox.text = if (!s.isNullOrBlank()) s.toString() else (origEditText.text.toString().ifBlank { "Preview" })
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Font Weight / Boldness Section
        val weightHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).roundToInt(), 0, (2 * density).roundToInt())
        }
        val weightTitleLabel = TextView(context).apply {
            text = "FONT WEIGHT / وزن الخط"
            textSize = 11f
            setTextColor(0xFF38BDF8.toInt())
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val weightValueLabel = TextView(context).apply {
            val name = when (selectedFontWeight) {
                400 -> "Regular (400)"
                500 -> "Medium (500)"
                600 -> "SemiBold (600)"
                700 -> "Bold (700)"
                800 -> "ExtraBold (800)"
                900 -> "Black (900)"
                else -> "$selectedFontWeight"
            }
            text = name
            textSize = 11f
            setTextColor(0xFF34D399.toInt())
            paint.isFakeBoldText = true
        }
        weightHeader.addView(weightTitleLabel)
        weightHeader.addView(weightValueLabel)
        card.addView(weightHeader)

        // Font weight chips (Regular 400, Medium 500, SemiBold 600, Bold 700, ExtraBold 800, Black 900)
        val weightScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, (2 * density).roundToInt(), 0, (6 * density).roundToInt())
        }
        val weightRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val weightButtons = mutableListOf<Button>()

        fun updateWeightUI(newWeight: Int) {
            selectedFontWeight = newWeight
            val name = when (newWeight) {
                400 -> "Regular (400)"
                500 -> "Medium (500)"
                600 -> "SemiBold (600)"
                700 -> "Bold (700)"
                800 -> "ExtraBold (800)"
                900 -> "Black (900)"
                else -> "$newWeight"
            }
            weightValueLabel.text = name

            // Update preview box live
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                previewBox.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, newWeight, false)
            } else {
                previewBox.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (newWeight >= 600) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            previewBox.paint.isFakeBoldText = (newWeight >= 600)
            previewBox.invalidate()

            // Update button styles
            for (btn in weightButtons) {
                val btnWeight = btn.tag as? Int ?: 400
                val isSelected = btnWeight == newWeight
                (btn.background as? GradientDrawable)?.setColor(if (isSelected) 0xFF0284C7.toInt() else 0xFF334155.toInt())
            }
        }

        val weightOptions = listOf(
            400 to "Regular",
            500 to "Medium",
            600 to "SemiBold",
            700 to "Bold (عريض)",
            800 to "ExtraBold",
            900 to "Black (ثقيل)"
        )

        for ((wVal, wLabel) in weightOptions) {
            val btn = Button(context).apply {
                text = wLabel
                tag = wVal
                textSize = 10f
                setTextColor(Color.WHITE)
                val padB = (4 * density).roundToInt()
                setPadding(padB, padB, padB, padB)
                background = GradientDrawable().apply {
                    setColor(if (wVal == selectedFontWeight) 0xFF0284C7.toInt() else 0xFF334155.toInt())
                    cornerRadius = 8f * density
                }
                val lpBtn = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (30 * density).roundToInt()
                ).apply {
                    marginEnd = (4 * density).roundToInt()
                }
                layoutParams = lpBtn
                setOnClickListener {
                    updateWeightUI(wVal)
                }
            }
            weightButtons.add(btn)
            weightRow.addView(btn)
        }
        weightScroll.addView(weightRow)
        card.addView(weightScroll)

        // Text Width Control (Slider & Presets)
        val widthHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * density).roundToInt(), 0, (2 * density).roundToInt())
        }
        val widthLabel = TextView(context).apply {
            text = "TEXT WIDTH / عرض النص"
            textSize = 11f
            setTextColor(0xFF38BDF8.toInt())
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val widthValueLabel = TextView(context).apply {
            text = "100%"
            textSize = 12f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
        }
        widthHeader.addView(widthLabel)
        widthHeader.addView(widthValueLabel)
        card.addView(widthHeader)

        val widthSeekBar = android.widget.SeekBar(context).apply {
            max = 150 // 0 to 150 -> 50% to 200%
            progress = 50 // 100%
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val scale = 0.5f + (progress / 100f)
                    selectedWidthScale = scale
                    previewBox.scaleX = scale
                    widthValueLabel.text = "${(scale * 100).toInt()}%"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        card.addView(widthSeekBar)

        // Width quick preset buttons (80%, 100%, 120%, 150%)
        val widthPresetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * density).roundToInt(), 0, (6 * density).roundToInt())
        }
        val presets = listOf(0.8f to "80%", 1.0f to "100%", 1.2f to "120%", 1.5f to "150%")
        for ((scale, text) in presets) {
            val btn = Button(context).apply {
                this.text = text
                textSize = 10f
                setTextColor(Color.WHITE)
                val padB = (4 * density).roundToInt()
                setPadding(padB, padB, padB, padB)
                background = GradientDrawable().apply {
                    setColor(0xFF334155.toInt())
                    cornerRadius = 8f * density
                }
                val lpBtn = LinearLayout.LayoutParams(0, (28 * density).roundToInt(), 1f).apply {
                    setMargins((2 * density).roundToInt(), 0, (2 * density).roundToInt(), 0)
                }
                layoutParams = lpBtn
                setOnClickListener {
                    selectedWidthScale = scale
                    previewBox.scaleX = scale
                    widthSeekBar.progress = ((scale - 0.5f) * 100).toInt()
                    widthValueLabel.text = "${(scale * 100).toInt()}%"
                }
            }
            widthPresetRow.addView(btn)
        }
        card.addView(widthPresetRow)

        // Color Palettes Row (Background / Text presets)
        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * density).roundToInt(), 0, (6 * density).roundToInt())
        }
        val colorInfo = TextView(context).apply {
            text = "Colors: Sampled auto-fill"
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        colorRow.addView(colorInfo)

        // Invert colors button
        val invertBtn = Button(context).apply {
            text = "Swap Colors"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF334155.toInt())
                cornerRadius = 12f * density
            }
            setOnClickListener {
                val temp = selectedBgColor
                selectedBgColor = selectedTextColor
                selectedTextColor = temp
                previewBox.setTextColor(selectedTextColor)
                (previewBox.background as GradientDrawable).setColor(selectedBgColor)
            }
        }
        colorRow.addView(invertBtn)
        card.addView(colorRow)

        // Action Buttons Row (Cancel, Save)
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (10 * density).roundToInt(), 0, 0)
        }

        val cancelBtn = Button(context).apply {
            text = "Cancel"
            setTextColor(0xFF94A3B8.toInt())
            background = null
            setOnClickListener {
                dialogContainer.removeAllViews()
                removeView(dialogContainer)
                activeDialogView = null
            }
        }
        buttonRow.addView(cancelBtn)

        val confirmBtn = Button(context).apply {
            text = "Apply & Save"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF0284C7.toInt())
                cornerRadius = 14f * density
            }
            setOnClickListener {
                val original = origEditText.text.toString().trim().ifBlank { item.text.trim() }
                val replacement = repEditText.text.toString().trim()

                if (original.isNotBlank() && replacement.isNotBlank()) {
                    val rule = ReplacementRule(
                        originalText = original,
                        replacementText = replacement,
                        packageName = item.packageName,
                        viewId = item.viewId,
                        className = item.className,
                        textColor = selectedTextColor,
                        backgroundColor = selectedBgColor,
                        textSizeSp = targetTextSize,
                        isBold = selectedFontWeight >= 600,
                        isItalic = item.isItalic,
                        matchMode = selectedMatchMode,
                        boundsLeft = item.bounds.left,
                        boundsTop = item.bounds.top,
                        boundsRight = item.bounds.right,
                        boundsBottom = item.bounds.bottom,
                        widthScale = selectedWidthScale,
                        fontWeight = selectedFontWeight
                    )
                    onRuleConfirmed(rule)
                    dismissSelf()
                }
            }
        }
        buttonRow.addView(confirmBtn)
        card.addView(buttonRow)

        val cardLp = LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.90f).roundToInt(),
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        dialogContainer.addView(card, cardLp)

        addView(
            dialogContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        activeDialogView = dialogContainer
    }

    private fun createInputBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(0xFF0F172A.toInt())
            cornerRadius = 10f * density
            setStroke((1f * density).roundToInt(), 0xFF334155.toInt())
        }
    }

    private fun dismissSelf() {
        try {
            windowManager.removeView(this)
        } catch (e: Exception) {
            // Already removed
        }
        onDismiss()
    }
}
