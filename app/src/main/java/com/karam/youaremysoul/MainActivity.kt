package com.karam.youaremysoul

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFFFB6D5),
                secondary = Color(0xFFFFD68A),
                background = Color(0xFF100B1B),
                surface = Color(0xFF21152D)
            )) {
                SoulWorldApp()
            }
        }
    }
}
