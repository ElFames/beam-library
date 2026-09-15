import Foundation
import CryptoKit

// Cripto nativa de iOS para Aircom/Beam, en Swift sobre CryptoKit (no Security.framework):
// CryptoKit no es accesible directamente desde Kotlin/Native (es Swift-only, sin cabecera
// C/ObjC), así que este módulo se compila aparte con `swiftc -emit-objc-header-path` y se
// consume desde `beam/src/iosMain` vía cinterop contra la cabecera generada. Ver
// beam/native/ios/build.sh.
//
// Formatos de bytes elegidos para ser compatibles con el resto de Aircom (Kotlin/JVM,
// firmware ESP32 en mbedTLS) — validado a mano contra Security.framework/mbedTLS antes de
// escribir esto (ver el spike de la Fase B del plan):
// - Clave pública: x963Representation (0x04 || X || Y, 65 bytes, punto EC sin comprimir) —
//   el wrapping a SubjectPublicKeyInfo DER (lo que de verdad viaja por el wire) se hace en
//   Kotlin (iosMain), no aquí — este módulo solo habla en representaciones nativas de CryptoKit.
// - Firma: derRepresentation (ASN.1 SEQUENCE) — mismo formato que produce
//   java.security.Signature("SHA256withECDSA") y mbedtls_ecdsa_write_signature.
// - ECDH: bytes crudos de SharedSecret (vía ContiguousBytes) — mismo secreto crudo (coordenada
//   X) que KeyAgreement("ECDH").generateSecret() en Java, sin ningún KDF de por medio.
// - AES-256-GCM: nonce(12) + ciphertext + tag(16) concatenados — mismo layout que ya usa
//   SecureChannel.kt en JVM (`iv + cipher.doFinal(data)`, donde doFinal ya incluye el tag).

@objc public class BeamPrivateKeyHandle: NSObject {
    let signingKey: P256.Signing.PrivateKey
    let agreementKey: P256.KeyAgreement.PrivateKey

    init(signingKey: P256.Signing.PrivateKey, agreementKey: P256.KeyAgreement.PrivateKey) {
        self.signingKey = signingKey
        self.agreementKey = agreementKey
    }
}

@objc public class BeamKeyPairResult: NSObject {
    @objc public let privateKey: BeamPrivateKeyHandle
    @objc public let publicKeyRaw: NSData

    init(privateKey: BeamPrivateKeyHandle, publicKeyRaw: NSData) {
        self.privateKey = privateKey
        self.publicKeyRaw = publicKeyRaw
    }
}

@objc public enum BeamCryptoError: Int, Error {
    case invalidData
}

@objc public class BeamCryptoKit: NSObject {

    /**
     * Un único scalar EC de 32 bytes sirve para reconstruir tanto la clave de firma
     * (P256.Signing) como la de acuerdo de claves (P256.KeyAgreement) — paridad con el
     * único EC KeyPair que usan Java (java.security.KeyPair) y mbedTLS (mbedtls_ecp_keypair)
     * para las dos operaciones a la vez.
     */
    @objc public static func generateKeyPair() -> BeamKeyPairResult {
        let signingKey = P256.Signing.PrivateKey()
        let agreementKey = try! P256.KeyAgreement.PrivateKey(rawRepresentation: signingKey.rawRepresentation)
        let handle = BeamPrivateKeyHandle(signingKey: signingKey, agreementKey: agreementKey)
        return BeamKeyPairResult(privateKey: handle, publicKeyRaw: signingKey.publicKey.x963Representation as NSData)
    }

    @objc public static func encodePrivateKey(_ key: BeamPrivateKeyHandle) -> NSData {
        return key.signingKey.rawRepresentation as NSData
    }

    @objc public static func decodePrivateKey(_ raw: NSData) throws -> BeamPrivateKeyHandle {
        let bytes = raw as Data
        let signingKey = try P256.Signing.PrivateKey(rawRepresentation: bytes)
        let agreementKey = try P256.KeyAgreement.PrivateKey(rawRepresentation: bytes)
        return BeamPrivateKeyHandle(signingKey: signingKey, agreementKey: agreementKey)
    }

    @objc public static func sign(_ key: BeamPrivateKeyHandle, data: NSData) throws -> NSData {
        let signature = try key.signingKey.signature(for: data as Data)
        return signature.derRepresentation as NSData
    }

    @objc public static func verify(publicKeyRaw: NSData, data: NSData, signature: NSData) -> Bool {
        guard let pubKey = try? P256.Signing.PublicKey(x963Representation: publicKeyRaw as Data) else { return false }
        guard let sig = try? P256.Signing.ECDSASignature(derRepresentation: signature as Data) else { return false }
        return pubKey.isValidSignature(sig, for: data as Data)
    }

    @objc public static func ecdh(_ key: BeamPrivateKeyHandle, otherPublicKeyRaw: NSData) throws -> NSData {
        let otherPub = try P256.KeyAgreement.PublicKey(x963Representation: otherPublicKeyRaw as Data)
        let shared = try key.agreementKey.sharedSecretFromKeyAgreement(with: otherPub)
        return shared.withUnsafeBytes { Data($0) } as NSData
    }

    @objc public static func sha256(_ data: NSData) -> NSData {
        let digest = SHA256.hash(data: data as Data)
        return Data(digest) as NSData
    }

    @objc public static func aesGcmEncrypt(key: NSData, plaintext: NSData) throws -> NSData {
        let symKey = SymmetricKey(data: key as Data)
        let sealed = try AES.GCM.seal(plaintext as Data, using: symKey)
        var combined = Data()
        combined.append(sealed.nonce.withUnsafeBytes { Data($0) })
        combined.append(sealed.ciphertext)
        combined.append(sealed.tag)
        return combined as NSData
    }

    @objc public static func aesGcmDecrypt(key: NSData, ivCiphertextTag: NSData) throws -> NSData {
        let data = ivCiphertextTag as Data
        guard data.count >= 28 else { throw BeamCryptoError.invalidData } // 12 (iv) + 16 (tag) mínimo
        let iv = data.prefix(12)
        let tag = data.suffix(16)
        let ciphertext = data.dropFirst(12).dropLast(16)
        let symKey = SymmetricKey(data: key as Data)
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: iv), ciphertext: ciphertext, tag: tag)
        let plaintext = try AES.GCM.open(box, using: symKey)
        return plaintext as NSData
    }
}
