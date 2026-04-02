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
    onDismissPdfState: () -> Unit = {},
    epubState: EpubConvertState = EpubConvertState.Idle,
    onConvertToEpub: (Uri, String) -> Unit = { _, _ -> },
    onDismissEpubState: () -> Unit = {}
) {
    val context = LocalContext.current

    val readLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onFileSelected(it) } }

    val pdfConvertLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val displayName = resolveFileName(context, it) ?: "book"
            onConvertToPdf(it, displayName)
        }
    }

    val epubConvertLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val displayName = resolveFileName(context, it) ?: "document"
            onConvertToEpub(it, displayName)
        }
    }

    // ── PDF conversion dialog ──────────────────────────────────────────────
    when (val pdf = pdfState) {
        is PdfConvertState.Converting -> {
            ConvertingDialog(
                title = "Converting EPUB → PDF…",
                current = pdf.current,
                total = pdf.total,
                info = pdf.chapterTitle
            )
        }
        is PdfConvertState.Done -> {
            DoneDialog(
                title = "EPUB → PDF Complete ✓",
                message = "Saved to Downloads:\n${pdf.fileName}",
                onDismiss = onDismissPdfState
            )
        }
        is PdfConvertState.Error -> {
            ErrorDialog(
                title = "EPUB → PDF Failed",
                message = pdf.message,
                onDismiss = onDismissPdfState
            )
        }

        else -> Unit
    }

    // ── EPUB conversion dialog ─────────────────────────────────────────────
    when (val epub = epubState) {
        is EpubConvertState.Converting -> {
            ConvertingDialog(
                title = "Converting PDF → EPUB…",
                current = epub.current,
                total = epub.total,
                info = epub.info
            )
        }

        is EpubConvertState.Done -> {
            DoneDialog(
                title = "PDF → EPUB Complete ✓",
                message = "Saved to Downloads:\n${epub.fileName}",
                onDismiss = onDismissEpubState
            )
        }

        is EpubConvertState.Error -> {
            ErrorDialog(
                title = "PDF → EPUB Failed",
                message = epub.message,
                onDismiss = onDismissEpubState
            )
        }
        else -> Unit
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("EPUB Reader") }) }
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
                text = "EPUB Reader & Converter",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Pick a file from your device storage",
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
                    .fillMaxWidth(0.75f)
                    .height(52.dp)
            ) {
                Text("📖  Read EPUB File")
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Button 2: EPUB → PDF
            OutlinedButton(
                onClick = {
                    pdfConvertLauncher.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/octet-stream"
                        )
                    )
                },
                enabled = pdfState !is PdfConvertState.Converting &&
                        epubState !is EpubConvertState.Converting,
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(52.dp)
            ) {
                Text("📄  Convert EPUB → PDF")
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Button 3: PDF → EPUB
            OutlinedButton(
                onClick = {
                    epubConvertLauncher.launch(arrayOf("application/pdf"))
                },
                enabled = pdfState !is PdfConvertState.Converting &&
                        epubState !is EpubConvertState.Converting,
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(52.dp)
            ) {
                Text("📚  Convert PDF → EPUB")
            }
        }
    }
}

// ── Reusable dialogs ──────────────────────────────────────────────────────

@Composable
private fun ConvertingDialog(title: String, current: Int, total: Int, info: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { current.toFloat() / total },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$current / $total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    CircularProgressIndicator()
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    textAlign = TextAlign.Center
                )
            }
        }
    )
}

@Composable
private fun DoneDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) }
    )
}

@Composable
private fun ErrorDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(title) },
        text = { Text(message) }
    )
}

private fun resolveFileName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(idx)
        }
    } catch (e: Exception) {
        null
    }
}
