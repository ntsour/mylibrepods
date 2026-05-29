package io.nikos.propods.presentation.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.nikos.propods.presentation.components.StyledButton
import io.nikos.propods.presentation.components.StyledScaffold
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun GitHubIssuesScreen() {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    
    StyledScaffold(title = "GitHub Issues") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_github_issues")
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text("Report bugs or request features on GitHub.", style = TextStyle(fontSize = 16.sp, fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                Spacer(Modifier.height(16.dp))
                StyledButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ntsour/propods/issues")))
                }, backdrop = rememberLayerBackdrop(), modifier = Modifier.fillMaxWidth()) {
                    Text("Open GitHub Issues", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = Color.White))
                }
            }
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
