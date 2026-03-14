import sys

with open("app/src/main/java/com/omama/stationalarm/ui/screens/MapSearchScreen.kt", "r", encoding="utf-8") as f:
    text = f.read()

start_idx = text.find("@Composable\nprivate fun PinBottomSheet(")
if start_idx == -1:
    start_idx = text.find("@Composable\r\nprivate fun PinBottomSheet(")

if start_idx != -1:
    end_idx = text.find("}\n\n@Composable\nprivate fun OsmMapView(", start_idx)
    if end_idx == -1:
         end_idx = text.find("}\r\n\r\n@Composable\r\nprivate fun OsmMapView(", start_idx)
    
    if end_idx == -1:
         end_idx = text.find("}\n\n\n\n@Composable\nprivate fun OsmMapView(", start_idx)
         
    if end_idx == -1:
         end_idx = text.find("}\r\n\r\n\r\n\r\n@Composable\r\nprivate fun OsmMapView(", start_idx)

    if end_idx != -1:
        new_text = text[:start_idx] + """@Composable
private fun MapMarkerOverlay(
    result     : GeoSearchResult,
    onSaveClick: () -> Unit,
    onAlarmClick: () -> Unit,
    onBack     : () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape    = RoundedCornerShape(16.dp),
        color    = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Location name + coords
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MapAccentBlueDim),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        ImageVector.vectorResource(R.drawable.ic_location_on),
                        null,
                        tint = MapAccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        result.name,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 15.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        result.subtitle.ifBlank { "${String.format("%.4f", result.lat)}, ${String.format("%.4f", result.lon)}" },
                        fontSize = 12.sp,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }

            Spacer(Modifier.height(16.dp))
            
            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick  = onSaveClick,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(12.dp),
                    border   = ButtonDefaults.outlinedButtonBorder(true).copy(width = 1.dp)
                ) {
                    Icon(
                        ImageVector.vectorResource(R.drawable.ic_star),
                        null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Save", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick  = onAlarmClick,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Use This", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun BottomPerimeterSlider(
    radiusKm: Double,
    onRadius: (Double) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape    = RoundedCornerShape(16.dp),
        color    = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text("Alert Radius", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${String.format("%.1f", radiusKm)} km",
                    fontWeight = FontWeight.Bold,
                    fontSize   = 14.sp,
                    color      = MapAccentBlue
                )
            }
            Spacer(Modifier.height(4.dp))
            Slider(
                value        = radiusKm.toFloat(),
                onValueChange = { onRadius(it.toDouble()) },
                valueRange   = 2f..20f,
                modifier     = Modifier.fillMaxWidth(),
                colors       = SliderDefaults.colors(
                    thumbColor       = MapAccentBlue,
                    activeTrackColor = MapAccentBlue,
                    inactiveTrackColor = MapAccentBlueDim
                )
            )
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("2 km", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                Text("20 km", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
        }
    }
}

""" + text[end_idx:]

        with open("app/src/main/java/com/omama/stationalarm/ui/screens/MapSearchScreen.kt", "w", encoding="utf-8") as f:
            f.write(new_text)
        print("Success!")
    else:
        print("End index not found around: ")
        print(text[start_idx+1000:start_idx+1500])
else:
    print("Start index not found")
