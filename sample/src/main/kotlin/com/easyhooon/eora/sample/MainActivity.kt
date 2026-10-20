package com.easyhooon.eora.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.easyhooon.eora.Eora

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Checkout", style = MaterialTheme.typography.headlineMedium)
                    Text("Order total: 12,300 KRW (should be 12,000 KRW)")
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .background(Color(0xFFFFF3E0)),
                        contentAlignment = Alignment.Center,
                    ) { Text("Card 1234-5678-9012-3456 (hide me in the editor)") }
                    Text("Shake the device to report, or use the buttons below. In the prod flavor they do nothing.")
                    Button(onClick = { Eora.startReport() }) { Text("Report a problem") }
                    OutlinedButton(onClick = { Eora.startRecording() }) { Text("Record the screen") }
                    OutlinedButton(onClick = { Eora.openDrafts() }) { Text("Eora drafts") }
                }
            }
        }
    }
}
