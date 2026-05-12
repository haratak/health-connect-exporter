package dev.harataku.healthconnectexporter

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = resources.getDimensionPixelSize(android.R.dimen.app_icon_size) / 2
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        layout.addView(TextView(this).apply {
            text = "Health Connect Exporter uses Health Connect read permissions only to show your active calories, total calories, and steps inside this app. It does not upload data in this milestone."
            textSize = 18f
            setLineSpacing(0f, 1.15f)
        })

        setContentView(layout)
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                padding + bars.left,
                padding + bars.top,
                padding + bars.right,
                padding + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(layout)
    }
}
