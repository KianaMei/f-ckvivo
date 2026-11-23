const DIGITS: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// Reads standard base64, padded or not. None for anything else: another alphabet, white space, misplaced padding.
pub fn decode(text: &str) -> Option<Vec<u8>> {
    let digits = text.trim_end_matches('=').as_bytes();
    let padding = text.len() - digits.len();
    // Padding, when there is any, fills the last group; a lone digit carries no whole byte.
    if padding > 2 || (padding > 0 && !text.len().is_multiple_of(4)) || digits.len() % 4 == 1 {
        return None;
    }
    let mut bytes = Vec::with_capacity(digits.len() / 4 * 3 + 2);
    for group in digits.chunks(4) {
        let mut value = 0u32;
        for digit in group {
            let six = match digit {
                b'A'..=b'Z' => digit - b'A',
                b'a'..=b'z' => digit - b'a' + 26,
                b'0'..=b'9' => digit - b'0' + 52,
                b'+' => 62,
                b'/' => 63,
                _ => return None,
            };
            value = value << 6 | u32::from(six);
        }
        value <<= 6 * (4 - group.len());
        let whole = [(value >> 16) as u8, (value >> 8) as u8, value as u8];
        bytes.extend_from_slice(&whole[..group.len() - 1]);
    }
    Some(bytes)
}

/// Writes standard base64 with padding.
pub fn encode(bytes: &[u8]) -> String {
    let mut text = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for group in bytes.chunks(3) {
        let value = group
            .iter()
            .fold(0u32, |value, byte| value << 8 | u32::from(*byte))
            << (8 * (3 - group.len()));
        for index in 0..4 {
            if index <= group.len() {
                text.push(DIGITS[(value >> (18 - 6 * index) & 63) as usize] as char);
            } else {
                text.push('=');
            }
        }
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_every_tail_length_padded_or_not_and_writes_it_back() {
        for bytes in [
            &b""[..],
            b"a",
            b"ab",
            b"abc",
            b"abcd",
            &[0, 255, 254, 3, 62],
        ] {
            let padded = encode(bytes);
            assert_eq!(decode(&padded).as_deref(), Some(bytes));
            assert_eq!(decode(padded.trim_end_matches('=')).as_deref(), Some(bytes));
        }
        assert_eq!(encode(b"ab"), "YWI=");
        for bad in [
            "a", "ab=", "a===", "ab==cd", "ab cd", "ab-_", "abcde", "=", "abc==",
        ] {
            assert_eq!(decode(bad), None, "{bad}");
        }
    }
}
