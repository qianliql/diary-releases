package io.github.qianliql.diary.media

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider

class CameraFileProvider : FileProvider()

/** The camera gets access to one staging file, never an app-owned pending MediaStore row. */
class CapturePhoto : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri) = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
        putExtra(MediaStore.EXTRA_OUTPUT, input)
        clipData = ClipData.newRawUri("photo", input)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == Activity.RESULT_OK
}

data class CameraPhoto(val uri: Uri, val inGallery: Boolean)
