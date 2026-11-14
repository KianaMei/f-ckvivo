/* Minimal stand-in for bionic's <sys/system_properties.h> so the exploit can be
 * built with a plain aarch64-linux (musl) toolchain and linked statically.
 * Only __system_property_get and PROP_VALUE_MAX are used by the source. */
#ifndef CVE64560_BIONIC_COMPAT_H
#define CVE64560_BIONIC_COMPAT_H

#define PROP_VALUE_MAX 92
#define PROP_NAME_MAX 32

#ifdef __cplusplus
extern "C" {
#endif

/* Returns the length of the property value (0 when unset/empty/unknown). */
int __system_property_get(const char *name, char *value);

#ifdef __cplusplus
}
#endif

#endif /* CVE64560_BIONIC_COMPAT_H */
