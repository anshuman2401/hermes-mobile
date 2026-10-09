package com.hermes.bot.feature.connect

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Local eye vectors avoid an extended material-icons dependency. */
object PasswordVisibilityIcons {
    val Visible: ImageVector by lazy {
        ImageVector.Builder("Visibility", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                addPath(PathParser().parsePathString("M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5C21.27,7.61 17,4.5 12,4.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z").toNodes())
            }
        }.build()
    }
    val Hidden: ImageVector by lazy {
        ImageVector.Builder("VisibilityOff", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                addPath(PathParser().parsePathString("M3.27,2 2,3.27 4.73,6C3.17,7.25 1.91,8.9 1,11c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.31 4.38,-0.86L20.73,22 22,20.73zM12,16.5c-2.49,0 -4.5,-2.01 -4.5,-4.5 0,-0.58 0.12,-1.13 0.32,-1.64l1.56,1.56C9.14,12.6 9.4,13.5 10.5,13.5c0.58,0 1.07,-0.25 1.42,-0.64l2.16,2.16c-0.62,0.31 -1.33,0.48 -2.08,0.48zM12,5.5c5,0 9.27,3.11 11,7.5 -0.56,1.42 -1.49,2.69 -2.67,3.72l-1.43,-1.43c0.65,-0.63 1.17,-1.39 1.5,-2.29 -1.46,-3.11 -4.61,-5.5 -8.4,-5.5 -0.86,0 -1.7,0.13 -2.49,0.37L7.94,6.3C9.2,5.78 10.57,5.5 12,5.5z").toNodes())
            }
        }.build()
    }
}
