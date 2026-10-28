package com.ef64nagaoka.g2cam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log

/**
 * 写真・動画の保存先。
 * - 既定：写真アプリの DCIM/G2Camera（MediaStore）
 * - 利用者がフォルダを選んだとき：そのフォルダ（Storage Access Framework）に直接書き込む
 *   （選んだフォルダが消えた・権限が外れたときは、既定に戻して保存する）
 */
object Storage {
    private const val TAG = "G2Cam"
    const val RELATIVE_DIR = "DCIM/G2Camera"

    /** 保存先に選ばれたフォルダ。権限が生きているときだけ返す */
    fun tree(context: Context): Uri? {
        val s = Prefs.saveTree(context) ?: return null
        val uri = Uri.parse(s)
        val ok = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (ok) uri else null
    }

    /** 画面に出す保存先の説明 */
    fun describe(context: Context): String {
        val s = Prefs.saveTree(context) ?: return "写真アプリ（DCIM/G2Camera）"
        val uri = Uri.parse(s)
        if (tree(context) == null) return "選んだフォルダに書き込めません（選び直してください）。いまは写真アプリ（DCIM/G2Camera）に保存"
        return try {
            val id = DocumentsContract.getTreeDocumentId(uri) // 例：primary:Pictures/G2
            val vol = id.substringBefore(':')
            val path = id.substringAfter(':', "")
            val where = if (vol == "primary") "内部ストレージ" else "SD カード等（$vol）"
            if (path.isEmpty()) where else "$where/$path"
        } catch (e: Exception) {
            uri.toString()
        }
    }

    private fun createInTree(context: Context, tree: Uri, name: String, mime: String): Uri? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
    }

    /** 写真（JPEG）をすぐに保存する。保存した URI を返す（保存できなければ null） */
    fun saveImage(context: Context, bytes: ByteArray, name: String): Uri? {
        val resolver = context.contentResolver
        val tree = tree(context)
        if (tree != null) {
            try {
                val uri = createInTree(context, tree, "$name.jpg", "image/jpeg")
                if (uri != null) {
                    resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    return uri
                }
            } catch (e: Exception) {
                Log.w(TAG, "選んだフォルダへの保存に失敗。既定に保存します", e)
            }
        }
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "写真の保存に失敗", e)
            null
        }
    }

    /** 選んだフォルダに動画ファイルを作る。選んでいない・作れないときは null（既定の MediaStore を使う） */
    class VideoTarget(val uri: Uri, val pfd: ParcelFileDescriptor)

    fun openVideoInTree(context: Context, name: String): VideoTarget? {
        val tree = tree(context) ?: return null
        return try {
            val uri = createInTree(context, tree, name, "video/mp4") ?: return null
            val pfd = context.contentResolver.openFileDescriptor(uri, "w") ?: return null
            VideoTarget(uri, pfd)
        } catch (e: Exception) {
            Log.w(TAG, "選んだフォルダに動画を作れません。既定に保存します", e)
            null
        }
    }

    /** 選んだフォルダの中から名前で探す（サービスを再起動したあとでも取り寄せられるように） */
    fun findInTree(context: Context, name: String): Uri? {
        val tree = tree(context) ?: return null
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "findInTree failed", e)
            null
        }
    }

    /** 中身が空の動画ファイルを消す（録画が失敗したとき） */
    fun deleteQuietly(context: Context, uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            Log.w(TAG, "delete failed", e)
        }
    }
}
