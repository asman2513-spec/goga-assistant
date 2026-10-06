package app.goga.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun CalendarSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.calendar_title), style = MaterialTheme.typography.titleLarge)
        Text(text = stringResource(R.string.calendar_body), style = MaterialTheme.typography.bodyLarge)
    }
}
