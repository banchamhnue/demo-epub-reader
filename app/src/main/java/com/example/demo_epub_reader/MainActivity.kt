package com.example.demo_epub_reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.demo_epub_reader.reader.*
import com.example.demo_epub_reader.ui.theme.DemoepubreaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DemoepubreaderTheme {
                EpubReaderApp()
            }
        }
    }
}

@Composable
fun EpubReaderApp(
    viewModel: EpubReaderViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pdfState by viewModel.pdfState.collectAsStateWithLifecycle()
    val epubState by viewModel.epubState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is ReaderUiState.Idle -> {
            HomeScreen(
                onFileSelected = { uri -> viewModel.loadEpubFromUri(uri) },
                pdfState = pdfState,
                onConvertToPdf = { uri, name -> viewModel.convertEpubToPdf(uri, name) },
                onDismissPdfState = { viewModel.resetPdfState() },
                epubState = epubState,
                onConvertToEpub = { uri, name -> viewModel.convertPdfToEpub(uri, name) },
                onDismissEpubState = { viewModel.resetEpubState() }
            )
        }

        is ReaderUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Parsing EPUB file…",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        is ReaderUiState.Success -> {
            ReaderScreen(
                state = state,
                onUpdateVisibleChapter = { itemIndex ->
                    viewModel.updateVisibleChapter(itemIndex)
                },
                onBack = {
                    viewModel.resetToIdle()
                },
                onGetImageData = { path, callback ->
                    viewModel.getImageData(path, callback)
                }
            )
        }

        is ReaderUiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = "Error loading EPUB",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { viewModel.resetToIdle() }) {
                        Text("Try Again")
                    }
                }
            }
        }
    }
}