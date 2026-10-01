import type { T } from '@deltachat/jsonrpc-client'

/** Full `MessageData` for a plain text message, as expected by `rpc.sendMsg`. */
export function textMessageData(text: string): T.MessageData {
  return {
    file: null,
    filename: null,
    viewtype: null,
    html: null,
    location: null,
    overrideSenderName: null,
    quotedMessageId: null,
    quotedText: null,
    text,
  }
}
