package com.jscyril.ebb.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jscyril.ebb.R

/** Insights tab placeholder; Task 10 replaces it with the dashboard. */
@Composable
fun DashboardPlaceholder(contentPadding: PaddingValues = PaddingValues()) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Illustration(MaterialShapes.Clover8Leaf, 160.dp)
        Text(stringResource(R.string.tab_insights), style = MaterialTheme.typography.headlineMedium)
        Body(stringResource(R.string.insights_soon))
    }
}
