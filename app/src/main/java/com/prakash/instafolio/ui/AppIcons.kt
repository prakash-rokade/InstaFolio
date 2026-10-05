package com.prakash.instafolio.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object AppIcons {
    val ContentCopy: ImageVector by lazy {
        materialIcon(
            "ContentCopy",
            "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11" +
                "c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z"
        )
    }

    val ContentPaste: ImageVector by lazy {
        materialIcon(
            "ContentPaste",
            "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2H5C3.9,2 3,2.9 3,4v16" +
                "c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V4C21,2.9 20.1,2 19,2zM12,2c0.55,0 1,0.45 1,1" +
                "s-0.45,1 -1,1s-1,-0.45 -1,-1S11.45,2 12,2zM19,20H5V4h2v3h10V4h2V20z"
        )
    }

    val Download: ImageVector by lazy {
        materialIcon("Download", "M5,20h14v-2H5V20zM19,9h-4V3H9v6H5l7,7L19,9z")
    }

    val Image: ImageVector by lazy {
        materialIcon(
            "Image",
            "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14" +
                "C20.1,21 21,20.1 21,19zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5L8.5,13.5z"
        )
    }

    val Movie: ImageVector by lazy {
        materialIcon(
            "Movie",
            "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4C2.9,4 2.01,4.9 2.01,6L2,18" +
                "c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4H18z"
        )
    }

    val PlayArrow: ImageVector by lazy {
        materialIcon("PlayArrow", "M8,5v14l11,-7z")
    }

    val Settings: ImageVector by lazy {
        materialIcon(
            "Settings",
            "M19.43,12.98c0.04,-0.32 0.07,-0.65 0.07,-0.98s-0.02,-0.66 -0.07,-0.98l2.11,-1.65" +
                "c0.19,-0.15 0.24,-0.42 0.12,-0.64l-2,-3.46c-0.12,-0.22 -0.37,-0.31 -0.6,-0.22l-2.49,1" +
                "c-0.52,-0.4 -1.08,-0.73 -1.69,-0.98L14.5,2.42C14.47,2.18 14.25,2 14,2h-4" +
                "c-0.25,0 -0.46,0.18 -0.5,0.42L9.12,5.07c-0.61,0.25 -1.17,0.59 -1.69,0.98l-2.49,-1" +
                "c-0.23,-0.08 -0.48,0 -0.6,0.22l-2,3.46c-0.13,0.22 -0.07,0.49 0.12,0.64l2.11,1.65" +
                "C4.52,11.34 4.5,11.67 4.5,12s0.02,0.66 0.07,0.98l-2.11,1.65c-0.19,0.15 -0.24,0.42 -0.12,0.64" +
                "l2,3.46c0.12,0.22 0.37,0.31 0.6,0.22l2.49,-1c0.52,0.4 1.08,0.73 1.69,0.98l0.38,2.65" +
                "c0.04,0.24 0.25,0.42 0.5,0.42h4c0.25,0 0.46,-0.18 0.5,-0.42l0.38,-2.65" +
                "c0.61,-0.25 1.17,-0.58 1.69,-0.98l2.49,1c0.23,0.08 0.48,0 0.6,-0.22l2,-3.46" +
                "c0.12,-0.22 0.07,-0.49 -0.12,-0.64L19.43,12.98zM12,15.5A3.5,3.5 0,1 1,12,8a3.5,3.5 0,0 1,0,7.5z"
        )
    }

    val PictureAsPdf: ImageVector by lazy {
        materialIcon(
            "PictureAsPdf",
            "M20,2H8C6.9,2 6,2.9 6,4v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM20,16H8V4h12V16z" +
                "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM16,12V9c0,-0.55 -0.45,-1 -1,-1h-2v5h2C15.55,13 16,12.55 16,12z" +
                "M14.5,11.5h-0.5v-2h0.5V11.5zM10.5,10.5h1V9h-2v4h1V10.5zM19,10.5h-1V9h1.5V8H16v5h1v-1.5h1V10.5z"
        )
    }

    val CheckCircle: ImageVector by lazy {
        materialIcon(
            "CheckCircle",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z"
        )
    }

    val Person: ImageVector by lazy {
        materialIcon(
            "Person",
            "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"
        )
    }

    val Close: ImageVector by lazy {
        materialIcon(
            "Close",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"
        )
    }

    val Sparkles: ImageVector by lazy {
        materialIcon(
            "Sparkles",
            "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM11.5,9.5L9,4 6.5,9.5 1,12l5.5,2.5L9,20l2.5,-5.5L17,12l-5.5,-2.5zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z"
        )
    }

    val PhotoLibrary: ImageVector by lazy {
        materialIcon(
            "PhotoLibrary",
            "M22,16V4c0,-1.1 -0.9,-2 -2,-2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2zM11,12l2.03,2.71L16,11l4,5H8l3,-4zM2,6v14c0,1.1 0.9,2 2,2h14v-2H4V6H2z"
        )
    }

    val Folder: ImageVector by lazy {
        materialIcon(
            "Folder",
            "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"
        )
    }

    val Lock: ImageVector by lazy {
        materialIcon(
            "Lock",
            "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM9,6c0,-1.66 1.34,-3 3,-3s3,1.34 3,3v2H9V6zM18,20H6V10h12v10zM12,17c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2z"
        )
    }

    val ChevronLeft: ImageVector by lazy {
        materialIcon(
            "ChevronLeft",
            "M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z"
        )
    }

    val ChevronRight: ImageVector by lazy {
        materialIcon(
            "ChevronRight",
            "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z"
        )
    }

    val MusicNote: ImageVector by lazy {
        materialIcon(
            "MusicNote",
            "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z"
        )
    }

    val History: ImageVector by lazy {
        materialIcon(
            "History",
            "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zm-1,5v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z"
        )
    }

    val Search: ImageVector by lazy {
        materialIcon(
            "Search",
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zm-6,0C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
        )
    }

    val Delete: ImageVector by lazy {
        materialIcon(
            "Delete",
            "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"
        )
    }

    val Share: ImageVector by lazy {
        materialIcon(
            "Share",
            "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92 1.61,0 2.92,-1.31 2.92,-2.92s-1.31,-2.92 -2.92,-2.92z"
        )
    }

    val Refresh: ImageVector by lazy {
        materialIcon(
            "Refresh",
            "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"
        )
    }

    val Fullscreen: ImageVector by lazy {
        materialIcon(
            "Fullscreen",
            "M7,14H5v5h5v-2H7v-3zm-2,-4h2V7h3V5H5v5zm12,7h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z"
        )
    }

    val ZoomIn: ImageVector by lazy {
        materialIcon(
            "ZoomIn",
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zm-6,0C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14zM12,10h-2v2H9v-2H7V9h2V7h1v2h2v1z"
        )
    }

    val Pause: ImageVector by lazy {
        materialIcon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z")
    }

    val VolumeUp: ImageVector by lazy {
        materialIcon(
            "VolumeUp",
            "M3,9v6h4l5,5V4L7,9H3zm13.5,3c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
        )
    }

    val VolumeOff: ImageVector by lazy {
        materialIcon(
            "VolumeOff",
            "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zm2.5,0c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z"
        )
    }

    val Favorite: ImageVector by lazy {
        materialIcon(
            "Favorite",
            "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
        )
    }

    val FavoriteBorder: ImageVector by lazy {
        materialIcon(
            "FavoriteBorder",
            "M16.5,3c-1.74,0 -3.41,0.81 -4.5,2.09C10.91,3.81 9.24,3 7.5,3 4.42,3 2,5.42 2,8.5c0,3.78 3.4,6.86 8.55,11.54L12,21.35l1.45,-1.32C18.6,15.36 22,12.28 22,8.5 22,5.42 19.58,3 16.5,3zm-4.4,15.55l-0.1,0.1 -0.1,-0.1C7.14,14.24 4,11.39 4,8.5 4,6.5 5.5,5 7.5,5c1.54,0 3.04,0.99 3.57,2.36h1.87C13.46,5.99 14.96,5 16.5,5c2,0 3.5,1.5 3.5,3.5 0,2.89 -3.14,5.74 -7.9,10.05z"
        )
    }

    val SwitchAccount: ImageVector by lazy {
        materialIcon(
            "SwitchAccount",
            "M6.5,12c1.93,0 3.5,-1.57 3.5,-3.5S8.43,5 6.5,5 3,6.57 3,8.5 4.57,12 6.5,12zm0,-5c0.83,0 1.5,0.67 1.5,1.5S7.33,10 6.5,10 5,9.33 5,8.5 5.67,7 6.5,7zm2.5,7h-5C2.01,14 0,16.01 0,18.5V20h14v-1.5c0,-2.49 -2.01,-4.5 -5,-4.5zm-5.5,4c0.72,-0.57 1.99,-1 3.5,-1s2.78,0.43 3.5,1H3.5zM17.5,12c1.93,0 3.5,-1.57 3.5,-3.5S19.43,5 17.5,5 14,6.57 14,8.5s1.57,3.5 3.5,3.5zm0,-5c0.83,0 1.5,0.67 1.5,1.5S18.33,10 17.5,10s-1.5,-0.67 -1.5,-1.5S16.67,7 17.5,7zm2.5,7h-1.59c1.01,0.85 1.59,2.11 1.59,3.5V20H24v-1.5c0,-2.49 -2.01,-4.5 -4,-4.5z"
        )
    }

    val GridView: ImageVector by lazy {
        materialIcon(
            "GridView",
            "M3,3v8h8V3H3zM9,9H5V5h4V9zM3,13v8h8v-8H3zM9,19H5v-4h4V19zM13,3v8h8V3H13zM19,9h-4V5h4V9zM13,13v8h8v-8H13zM19,19h-4v-4h4V19z"
        )
    }

    val CheckBox: ImageVector by lazy {
        materialIcon(
            "CheckBox",
            "M19,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.11,0 2,-0.9 2,-2V5c0,-1.1 -0.89,-2 -2,-2zm-9,14l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z"
        )
    }

    val CheckBoxOutlineBlank: ImageVector by lazy {
        materialIcon(
            "CheckBoxOutlineBlank",
            "M19,5v14H5V5h14m0,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2z"
        )
    }

    val SelectAll: ImageVector by lazy {
        materialIcon(
            "SelectAll",
            "M3,5h2V3c-1.1,0 -2,0.9 -2,2zm0,8h2v-2H3v2zm4,8h2v-2H7v2zM3,9h2V7H3v2zm10,-6h-2v2h2V3zm6,0v2h2c0,-1.1 -0.9,-2 -2,-2zm-6,18h2v-2h-2v2zM3,17h2v-2H3v2zm8,-14h-2v2h2V3zm8,14h2v-2h-2v2zm-4,4h2v-2h-2v2zm4,-8h2v-2h-2v2zm0,-4h2V7h-2v2zm-2,12c1.1,0 2,-0.9 2,-2h-2v2zm-6,-8h6v-6h-6v6zm2,-4h2v2h-2v-2z"
        )
    }

    val Verified: ImageVector by lazy {
        materialIcon(
            "Verified",
            "M12,1L9.7,3.6L6.2,3.3L4.9,6.5L1.8,7.9L2.3,11.3L0.4,14.1L2,17.1L1.8,20.5L5.1,21.5L6.9,24.3L10.3,23.4L12.9,25.6L15.5,23.4L18.9,24.3L20.7,21.5L24,20.5L23.8,17.1L25.4,14.1L23.5,11.3L24,7.9L20.9,6.5L19.6,3.3L16.1,3.6L13.8,1L12,1zM10.9,16.8L6.8,12.7L8.2,11.3L10.9,14L17.3,7.6L18.7,9L10.9,16.8z"
        )
    }
}

private fun materialIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()
