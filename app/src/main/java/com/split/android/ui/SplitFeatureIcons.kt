package com.split.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonochromePhotos
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Store
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object SplitFeatureIcons {
    val Wallet: ImageVector
        get() = LucideBitcoin

    val QrCodeScan: ImageVector
        get() = Icons.Rounded.QrCodeScanner

    val Feed: ImageVector
        get() = Icons.Rounded.MonochromePhotos

    val Store: ImageVector
        get() = Icons.Rounded.Store

    val Tag: ImageVector
        get() = Icons.Rounded.Sell

    val Contacts: ImageVector
        get() = LucideBookUser

    val Transactions: ImageVector
        get() = Icons.Rounded.Schedule

    val Rewards: ImageVector
        get() = LucideAtom
}

private val LucideBookUser: ImageVector by lazy {
    lucideIcon("LucideBookUser") {
        lucidePath {
            moveTo(15f, 13f)
            arcToRelative(3f, 3f, 0f, true, false, -6f, 0f)
        }
        lucidePath {
            moveTo(4f, 19.5f)
            verticalLineToRelative(-15f)
            arcTo(2.5f, 2.5f, 0f, false, true, 6.5f, 2f)
            horizontalLineTo(19f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, 1f)
            verticalLineToRelative(18f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
            horizontalLineTo(6.5f)
            arcToRelative(1f, 1f, 0f, false, true, 0f, -5f)
            horizontalLineTo(20f)
        }
        lucidePath {
            moveTo(14f, 8f)
            arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
            arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
        }
    }
}

private val LucideBitcoin: ImageVector by lazy {
    lucideIcon("LucideBitcoin") {
        lucidePath {
            moveTo(11.767f, 19.089f)
            curveToRelative(4.924f, 0.868f, 6.14f, -6.025f, 1.216f, -6.894f)
        }
        lucidePath {
            moveTo(11.767f, 19.089f)
            lineTo(5.86f, 18.047f)
        }
        lucidePath {
            moveTo(11.768f, 19.089f)
            lineToRelative(-0.347f, 1.97f)
        }
        lucidePath {
            moveTo(12.984f, 12.195f)
            curveToRelative(4.924f, 0.869f, 6.14f, -6.025f, 1.215f, -6.893f)
        }
        lucidePath {
            moveTo(12.984f, 12.195f)
            lineTo(9.044f, 11.501f)
        }
        lucidePath {
            moveTo(14.199f, 5.302f)
            lineTo(8.29f, 4.26f)
        }
        lucidePath {
            moveTo(14.198f, 5.302f)
            lineToRelative(0.348f, -1.97f)
        }
        lucidePath {
            moveTo(7.48f, 20.364f)
            lineTo(10.606f, 2.637f)
        }
    }
}

private val LucideAtom: ImageVector by lazy {
    lucideIcon("LucideAtom") {
        lucidePath {
            moveTo(13f, 12f)
            arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
            arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
        }
        lucidePath {
            moveTo(20.2f, 20.2f)
            curveToRelative(2.04f, -2.03f, 0.02f, -7.36f, -4.5f, -11.9f)
            curveToRelative(-4.54f, -4.52f, -9.87f, -6.54f, -11.9f, -4.5f)
            curveToRelative(-2.04f, 2.03f, -0.02f, 7.36f, 4.5f, 11.9f)
            curveToRelative(4.54f, 4.52f, 9.87f, 6.54f, 11.9f, 4.5f)
            close()
        }
        lucidePath {
            moveTo(15.7f, 15.7f)
            curveToRelative(4.52f, -4.54f, 6.54f, -9.87f, 4.5f, -11.9f)
            curveToRelative(-2.03f, -2.04f, -7.36f, -0.02f, -11.9f, 4.5f)
            curveToRelative(-4.52f, 4.54f, -6.54f, 9.87f, -4.5f, 11.9f)
            curveToRelative(2.03f, 2.04f, 7.36f, 0.02f, 11.9f, -4.5f)
            close()
        }
    }
}

private fun lucideIcon(
    name: String,
    block: ImageVector.Builder.() -> Unit
): ImageVector {
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()
}

private fun ImageVector.Builder.lucidePath(pathBuilder: PathBuilder.() -> Unit) {
    path(
        fill = SolidColor(Color.Transparent),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathFillType = PathFillType.NonZero
    ) {
        pathBuilder()
    }
}
