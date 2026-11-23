package io.github.nuke.oss.app.tiktok.sdk.chat

import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.fields
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.tiktok.query.ChatUploadQueries
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

// A picture file the host process can read, with the size its header declares. original asks the host's uploader to
// keep the file as it is instead of compressing it.
internal data class ImageFile(val file: File, val width: Int, val height: Int, val original: Boolean = false)

// The upload did not end with a resource: the host refused the file, the network failed, or no answer came in time.
// Nothing has been sent when this is thrown.
internal class ImageUploadFailed(message: String) : Exception(message)

// What a feature needs to send a picture: the content for ContentSender, with the file already uploaded.
internal fun interface ImageContents {
    // Blocks the calling thread, a worker, until the host's uploader answers or the timeout passes. The file must stay
    // as it is until the send that follows has ended. Throws ImageUploadFailed.
    fun content(image: ImageFile, timeoutMillis: Long): Any
}

// Uploads a picture through the host's upload manager, the entry its own chat uses, and builds the picture content
// from the resource the uploader answers with, as NukeApp's DyMsgHost does: an encrypted resource goes into the
// content the host's factory builds from a PhotoParam, a plain one into the plain picture content. The message type
// and the serialized form are the host's.
internal class ChatImageUploader(
    private val hostClassLoader: ClassLoader,
    private val manager: Method,
    private val upload: Method,
    private val uploaded: Method,
    private val errorCode: Field,
    private val pictureContent: Method,
) : ImageContents {
    private val listenerType: Class<*> = upload.parameterTypes[LISTENER]
    private val urlType = ChatUploadQueries.URL.toClass(hostClassLoader)

    init {
        check(uploaded.declaringClass == listenerType) { "The image upload listener changed" }
        listOf(manager, upload, uploaded, pictureContent).forEach { it.isAccessible = true }
        errorCode.isAccessible = true
    }

    override fun content(image: ImageFile, timeoutMillis: Long): Any {
        val path = image.file.path
        val answers = ArrayBlockingQueue<Result<Any>>(1)
        val listener = Proxy.newProxyInstance(hostClassLoader, arrayOf(listenerType)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                "toString" -> return@newProxyInstance "NukeImageUploadListener"
            }
            answer(method, args ?: emptyArray())?.let(answers::offer)
            null
        }
        val uploader = call(manager, null) ?: error("The upload manager is unavailable")
        // The action and save types of the host's own encrypted-image upload entry.
        call(upload, uploader, path, ACTION_TYPE, SAVE_TYPE, listener, path, image.original, false)
        val resource = (answers.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            ?: throw ImageUploadFailed("No answer from the uploader in ${timeoutMillis / 1000} s")).getOrThrow()
        return build(image, resource)
    }

    // Progress and retry notifications answer nothing; only the uploaded resource or the uploader's error ends the wait.
    private fun answer(event: Method, args: Array<out Any?>): Result<Any>? {
        if (event == uploaded) {
            // The flag tells a fresh upload from a cached one, not success from failure.
            val resource = args.getOrNull(RESOURCE)
            return if (resource != null && urlType.isInstance(resource)) Result.success(resource)
            else Result.failure(ImageUploadFailed("The uploader returned no resource"))
        }
        val error = args.singleOrNull()?.takeIf(errorCode.declaringClass::isInstance) ?: return null
        return Result.failure(ImageUploadFailed("${errorCode.get(error) ?: "?"}: ${error.toString().take(MAX_ERROR_CHARS)}"))
    }

    private fun build(image: ImageFile, resource: Any): Any {
        val path = image.file.path
        if (!ChatUploadQueries.ENCRYPT_URL.toClass(hostClassLoader).isInstance(resource)) {
            return create(ChatUploadQueries.PLAIN_PICTURE).also {
                write(it, "url", resource)
                write(it, "width", image.width)
                write(it, "height", image.height)
                write(it, "picturePath", path)
                write(it, "sendRaw", image.original)
            }
        }
        val photo = create(ChatUploadQueries.PHOTO).also {
            write(it, "path", path)
            write(it, "relativePath", path)
            write(it, "width", image.width)
            write(it, "height", image.height)
        }
        return (call(pictureContent, null, photo) ?: error("The picture factory returned no content")).also {
            write(it, "url", resource)
            write(it, "sendRaw", image.original)
        }
    }

    private fun create(type: String): Any =
        type.toClass(hostClassLoader).getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private fun write(owner: Any, name: String, value: Any?) {
        val field = FIELDS.getValue(name).of(owner) ?: error("${owner.javaClass.name} has no field $name")
        field.set(owner, value)
    }

    private fun call(method: Method, receiver: Any?, vararg args: Any?): Any? = try {
        method.invoke(receiver, *args)
    } catch (error: InvocationTargetException) {
        throw (error.targetException as? RuntimeException) ?: IllegalStateException(error.targetException)
    }

    private companion object {
        const val LISTENER = 3
        const val RESOURCE = 2
        const val ACTION_TYPE = 1
        const val SAVE_TYPE = 3
        const val MAX_ERROR_CHARS = 200
        val FIELDS = listOf("url", "width", "height", "picturePath", "sendRaw", "path", "relativePath").associateWith(::NamedField)
    }
}

// The host-backed uploader, built on first use from the located targets so a missing one fails that send, not the
// feature.
internal class HostImageContents(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : ImageContents {
    private val uploader by lazy {
        val methods = resolver.methods(hostClassLoader, METHODS)
        val errorCode = resolver.fields(hostClassLoader, ChatUploadQueries.UPLOAD_ERROR_CODE).single()
        ChatImageUploader(hostClassLoader, methods[0], methods[1], methods[2], errorCode, methods[3])
    }

    override fun content(image: ImageFile, timeoutMillis: Long) = uploader.content(image, timeoutMillis)

    // Every query the uploader resolves on first use; a feature that sends pictures declares them (see UsesDexQueries).
    companion object {
        private val METHODS = listOf(
            ChatUploadQueries.UPLOAD_MANAGER, ChatUploadQueries.UPLOAD_IMAGE, ChatUploadQueries.IMAGE_UPLOADED, ChatUploadQueries.PICTURE_CONTENT,
        )
        val QUERIES: List<DexQuery> = METHODS + ChatUploadQueries.UPLOAD_ERROR_CODE
    }
}
