//! The MCP server the module runs inside Douyin, so that an AI client the user trusts can look up contacts, send text
//! and pictures, claim the rewards of its streaks and mark its chats read as the signed-in account. Everything that does not need the app is here: the HTTP transport
//! (`http`), the access tokens with their permissions, reachable chats and rate limits (`token`), JSON-RPC and the MCP
//! methods (`rpc`), the tools and their rules (`tools`), the pictures a tool call may carry (`image`) and the server
//! that ties them to a socket on this device or its local networks (`server`). The app is reached only through `Host`,
//! which the Kotlin side implements (`bridge`); the tests bring a stand-in.

mod base64;
mod bridge;
mod http;
mod image;
mod rpc;
mod server;
mod token;
mod tools;

pub use image::{Image, ImageError, ImageFormat, MAX_IMAGE_BYTES};
pub use server::{Reach, Server};
pub use token::{
    issue, Config, Issued, Permission, Token, ENTROPY_BYTES, MAX_REQUESTS_PER_MINUTE, MAX_TOKENS,
    TOKEN_PREFIX,
};
pub use tools::{
    Claimed, Contact, ContactKind, Host, HostError, Reward, SendStatus, Sent, MAX_RESPONSE_BYTES,
};
