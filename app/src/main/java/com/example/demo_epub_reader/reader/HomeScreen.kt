package com.example.demo_epub_reader.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onFileSelected: (Uri) -> Unit,
    pdfState: PdfConvertState = PdfConvertState.Idle,
    onConvertToPdf: (Uri, String) -> Unit = { _, _ -> },
    onDismissPdfState: () -> Unit = {}
) {
    val context = LocalContext.current

    val readLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onFileSelected(it) }
    }

    val convertLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Resolve display name from URI
            val displayName = resolveFileName(context, it) ?: "book"
            onConvertToPdf(it, displayName)
        }
    }

    // PDF progress / result dialog
    when (val pdf = pdfState) {
        is PdfConvertState.Converting -> {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("Converting to PDF…") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (pdf.total > 0) {
                            LinearProgressIndicator(
                                progress = { pdf.current.toFloat() / pdf.total },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Chapter ${pdf.current}/${pdf.total}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = pdf.chapterTitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            )
        }

        is PdfConvertState.Done -> {
            AlertDialog(
                onDismissRequest = onDismissPdfState,
                confirmButton = {
                    TextButton(onClick = onDismissPdfState) { Text("OK") }
                },
                title = { Text("Conversion Complete ✓") },
                text = {
                    Text(
                        text = "Saved to Downloads:\n${pdf.fileName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            )
        }

        is PdfConvertState.Error -> {
            AlertDialog(
                onDismissRequest = onDismissPdfState,
                confirmButton = {
                    TextButton(onClick = onDismissPdfState) { Text("Close") }
                },
                title = { Text("Conversion Failed") },
                text = { Text(pdf.message) }
            )
        }

        else -> Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("EPUB Reader") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Open an EPUB file to start reading",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Pick a .epub file from your device storage",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))

            // Button 1: Read EPUB
            Button(
                onClick = {
                    readLauncher.launch(arrayOf("application/epub+zip", "application/octet-stream"))
                },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(52.dp)
            ) {
                Text("Read EPUB File")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Button 2: Convert to PDF
            OutlinedButton(
                onClick = {
                    convertLauncher.launch(arrayOf("application/epub+zip", "application/octet-stream"))
                },
                enabled = pdfState !is PdfConvertState.Converting,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(52.dp)
            ) {
                Text("Convert EPUB to PDF")
            }
        }
    }
}

private fun resolveFileName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        }
    } catch (e: Exception) {
        null
    }
}
