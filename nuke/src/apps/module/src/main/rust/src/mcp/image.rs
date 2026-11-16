use super::base64;

/// The largest picture a tool call may carry, decoded.
pub const MAX_IMAGE_BYTES: usize = 5 * 1024 * 1024;
/// The longest base64 text that can still decode to `MAX_IMAGE_BYTES`.
pub const MAX_IMAGE_CHARS: usize = MAX_IMAGE_BYTES.div_ceil(3) * 4;
const MAX_SIDE: u32 = 20_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ImageFormat {
    Jpeg,
    Png,
}

impl ImageFormat {
    /// The file extension the app stores the picture under.
    pub fn extension(self) -> &'static str {
        match self {
            ImageFormat::Jpeg => "jpg",
            ImageFormat::Png => "png",
        }
    }
}

/// A picture as a client sent it: the bytes of a JPEG or PNG file and the size its header declares. The pixels are
/// never decoded here; the app's own uploader does that.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Image {
    pub bytes: Vec<u8>,
    pub format: ImageFormat,
    pub width: u32,
    pub height: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ImageError {
    Encoding,
    TooLarge,
    Unsupported,
}

impl Image {
    /// Reads standard base64, padded or not, of a whole JPEG or PNG file.
    pub fn decode(text: &str) -> Result<Image, ImageError> {
        if text.len() > MAX_IMAGE_CHARS {
            return Err(ImageError::TooLarge);
        }
        let bytes = base64::decode(text).ok_or(ImageError::Encoding)?;
        if bytes.len() > MAX_IMAGE_BYTES {
            return Err(ImageError::TooLarge);
        }
        let (format, width, height) = png(&bytes)
            .or_else(|| jpeg(&bytes))
            .filter(|(_, width, height)| {
                (1..=MAX_SIDE).contains(width) && (1..=MAX_SIDE).contains(height)
            })
            .ok_or(ImageError::Unsupported)?;
        Ok(Image {
            bytes,
            format,
            width,
            height,
        })
    }
}

fn be32(bytes: &[u8], at: usize) -> Option<u32> {
    let field = bytes.get(at..at + 4)?;
    Some(u32::from_be_bytes([field[0], field[1], field[2], field[3]]))
}

fn be16(bytes: &[u8], at: usize) -> Option<u32> {
    let field = bytes.get(at..at + 2)?;
    Some(u32::from(u16::from_be_bytes([field[0], field[1]])))
}

// The signature, then the IHDR chunk, which is always first and starts with the width and the height.
fn png(bytes: &[u8]) -> Option<(ImageFormat, u32, u32)> {
    if !bytes.starts_with(b"\x89PNG\r\n\x1a\n") || bytes.get(12..16)? != b"IHDR" {
        return None;
    }
    Some((ImageFormat::Png, be32(bytes, 16)?, be32(bytes, 20)?))
}

// Walks the segments up to the first start-of-frame, which holds the height and then the width.
fn jpeg(bytes: &[u8]) -> Option<(ImageFormat, u32, u32)> {
    if !bytes.starts_with(&[0xff, 0xd8]) {
        return None;
    }
    let mut at = 2;
    loop {
        if *bytes.get(at)? != 0xff {
            return None;
        }
        let marker = *bytes.get(at + 1)?;
        match marker {
            // Fill bytes before a marker.
            0xff => at += 1,
            // Markers that stand alone.
            0x01 | 0xd0..=0xd8 => at += 2,
            // Every start-of-frame except the tables and the arithmetic conditioning that share its range.
            0xc0..=0xcf if !matches!(marker, 0xc4 | 0xc8 | 0xcc) => {
                return Some((
                    ImageFormat::Jpeg,
                    be16(bytes, at + 7)?,
                    be16(bytes, at + 5)?,
                ));
            }
            // The end of the image or the scan data came before any frame.
            0xd9 | 0xda => return None,
            _ => {
                let length = be16(bytes, at + 2)? as usize;
                if length < 2 {
                    return None;
                }
                at += 2 + length;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::base64::encode;
    use super::*;

    fn png_file(width: u32, height: u32) -> Vec<u8> {
        let mut file = b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR".to_vec();
        file.extend_from_slice(&width.to_be_bytes());
        file.extend_from_slice(&height.to_be_bytes());
        file.extend_from_slice(&[8, 6, 0, 0, 0]);
        file
    }

    fn jpeg_file(frame: u8, width: u16, height: u16) -> Vec<u8> {
        // SOI, an APP0 segment, a quantization table, fill bytes, then the frame.
        let mut file = vec![
            0xff, 0xd8, 0xff, 0xe0, 0, 4, b'J', b'F', 0xff, 0xdb, 0, 3, 0,
        ];
        file.extend_from_slice(&[0xff, 0xff, frame, 0, 11, 8]);
        file.extend_from_slice(&height.to_be_bytes());
        file.extend_from_slice(&width.to_be_bytes());
        file.extend_from_slice(&[1, 1, 0x11, 0]);
        file
    }

    #[test]
    fn a_png_and_a_jpeg_give_their_declared_size() {
        let png = Image::decode(&encode(&png_file(640, 480))).unwrap();
        assert_eq!(
            (png.format, png.width, png.height),
            (ImageFormat::Png, 640, 480)
        );
        assert_eq!(png.bytes, png_file(640, 480));
        for frame in [0xc0, 0xc2] {
            let jpeg = Image::decode(&encode(&jpeg_file(frame, 300, 200))).unwrap();
            assert_eq!(
                (jpeg.format, jpeg.width, jpeg.height),
                (ImageFormat::Jpeg, 300, 200)
            );
        }
    }

    #[test]
    fn anything_else_is_refused() {
        let refused = |bytes: &[u8]| Image::decode(&encode(bytes)).unwrap_err();
        assert_eq!(refused(b"GIF89a\x01\0\x01\0"), ImageError::Unsupported);
        assert_eq!(refused(b""), ImageError::Unsupported);
        assert_eq!(refused(&png_file(0, 10)), ImageError::Unsupported);
        assert_eq!(
            refused(&png_file(10, MAX_SIDE + 1)),
            ImageError::Unsupported
        );
        assert_eq!(refused(&png_file(10, 10)[..20]), ImageError::Unsupported);
        // A table marker in the frame range is not a frame, and a file that ends before one has no size.
        assert_eq!(refused(&jpeg_file(0xc4, 10, 10)), ImageError::Unsupported);
        assert_eq!(
            refused(&jpeg_file(0xc0, 10, 10)[..20]),
            ImageError::Unsupported
        );
        assert_eq!(
            refused(&[0xff, 0xd8, 0xff, 0xda, 0, 2]),
            ImageError::Unsupported
        );
        assert_eq!(
            refused(&[0xff, 0xd8, 0xff, 0xe0, 0, 0]),
            ImageError::Unsupported
        );
        assert_eq!(Image::decode("not base64!"), Err(ImageError::Encoding));
        assert_eq!(
            Image::decode(&"A".repeat(MAX_IMAGE_CHARS + 4)),
            Err(ImageError::TooLarge)
        );
    }
}
