package com.aster.files.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aster.files.ui.sort.FileSort
import com.aster.files.ui.sort.SortDirection
import com.aster.files.ui.sort.SortField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortBottomSheet(
    current: FileSort,
    onSelect: (FileSort) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                "排序方式",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "文件夹始终排在文件前面；大小/类型排序时文件夹按名称排序。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            SortField.values().forEach { field ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        field.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = current.field == field &&
                            current.direction == SortDirection.ASC,
                        onClick = {
                            onSelect(FileSort(field, SortDirection.ASC))
                            onDismiss()
                        },
                        label = { Text("升序") }
                    )
                    FilterChip(
                        selected = current.field == field &&
                            current.direction == SortDirection.DESC,
                        onClick = {
                            onSelect(FileSort(field, SortDirection.DESC))
                            onDismiss()
                        },
                        label = { Text("降序") }
                    )
                }
            }
        }
    }
}
