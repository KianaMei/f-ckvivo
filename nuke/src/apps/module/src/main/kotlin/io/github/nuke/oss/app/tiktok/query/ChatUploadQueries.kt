package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.uniqueClass
import io.github.nuke.oss.app.shared.dex.uniqueField
import io.github.nuke.oss.app.shared.dex.uniqueMethod
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

// Dex queries for sending a picture the way the host's own chat does: its upload manager's image entry with the
// listener that hears the uploaded resource, and the factory of the picture content. Taken from NukeApp's
// DyMsgDexTargets, checked there against Douyin 40.7.0. Each query looks up what it depends on with the same bridge,
// never another query's cached result.
@DexQueries
internal object ChatUploadQueries {
    const val URL = "com.ss.android.ugc.aweme.base.model.UrlModel"
    const val ENCRYPT_URL = "com.ss.android.ugc.aweme.im.sdk.chat.media.EncryptUrlModel"
    const val PHOTO = "com.ss.android.ugc.aweme.im.service.model.PhotoParam"
    const val PICTURE = "com.ss.android.ugc.aweme.im.sdk.chat.model.StoryPictureContent"
    const val PLAIN_PICTURE = "com.ss.android.ugc.aweme.im.sdk.chat.model.OnlyPictureContent"
    private const val IMAGE_LOG = "[ImageUpLoad] before upload, path = "

    private fun DexKitBridge.uploader(): String = uniqueClass { matcher { usingEqStrings(IMAGE_LOG) } }.name

    // The listener of the image upload, taken from the inner upload step that logs the path.
    private fun DexKitBridge.imageListener(): String = findMethod {
        matcher {
            usingEqStrings(IMAGE_LOG)
            returnType = "void"
            paramTypes("java.lang.String", "int", "int", null, "java.lang.String", "boolean", "boolean", null, null)
        }
    }.also { check(it.size == 1) { "expected one image upload step, found ${it.size}" } }.single().paramTypeNames[3]

    val UPLOAD_MANAGER = DexQuery { bridge ->
        val owner = bridge.uploader()
        bridge.uniqueMethod { matcher { declaredClass = owner; modifiers(Modifier.STATIC); returnType = owner; paramCount = 0 } }
    }

    // The path, the action and save types, the listener, the path again, "send the original" and a flag left off.
    val UPLOAD_IMAGE = DexQuery { bridge ->
        val owner = bridge.uploader()
        val listener = bridge.imageListener()
        bridge.uniqueMethod {
            matcher {
                declaredClass = owner
                returnType = "void"
                paramTypes("java.lang.String", "int", "int", listener, "java.lang.String", "boolean", "boolean")
            }
        }
    }

    val IMAGE_UPLOADED = DexQuery { bridge ->
        val listener = bridge.imageListener()
        bridge.uniqueMethod { matcher { declaredClass = listener; returnType = "void"; paramTypes("java.lang.String", "boolean", URL) } }
    }

    // The upload error's code, in the class whose description names the stage and the code.
    val UPLOAD_ERROR_CODE = DexQuery { bridge ->
        val owner = bridge.uniqueClass { matcher { usingEqStrings("errorStage=", ", errorCode=") } }.name
        bridge.uniqueField { matcher { declaredClass = owner; type = "java.lang.Long" } }
    }

    val PICTURE_CONTENT = method {
        matcher { declaredClass = PICTURE; modifiers(Modifier.STATIC); returnType = PICTURE; paramTypes(PHOTO) }
    }
}
