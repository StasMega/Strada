package com.stasmega.strada

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WelcomeScreen(onComplete: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "How should we\ncall you?",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                lineHeight = 40.sp
            )
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Your name") },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = { if (name.isNotBlank()) onComplete(name) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp), // Extra Large rounding
                colors = ButtonDefaults.buttonColors(containerColor = StradaBlue)
            ) {
                Text("Continue", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}