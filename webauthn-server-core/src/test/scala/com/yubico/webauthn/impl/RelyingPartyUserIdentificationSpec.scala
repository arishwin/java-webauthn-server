package com.yubico.webauthn.impl

import java.security.KeyPair
import java.util.Optional

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.yubico.scala.util.JavaConverters._
import com.yubico.u2f.crypto.BouncyCastleCrypto
import com.yubico.u2f.crypto.Crypto
import com.yubico.u2f.crypto.ChallengeGenerator
import com.yubico.u2f.data.messages.key.util.U2fB64Encoding
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.data.ArrayBuffer
import com.yubico.webauthn.data.AuthenticationExtensionsClientInputs
import com.yubico.webauthn.data.CollectedClientData
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.Base64UrlString
import com.yubico.webauthn.data.AuthenticatorAssertionResponse
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.RegisteredCredential
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions
import com.yubico.webauthn.test.TestAuthenticator
import com.yubico.webauthn.util.BinaryUtil
import com.yubico.webauthn.util.WebAuthnCodecs
import org.junit.runner.RunWith
import org.scalatest.FunSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import scala.collection.JavaConverters._
import scala.util.Success
import scala.util.Failure
import scala.util.Try


@RunWith(classOf[JUnitRunner])
class RelyingPartyUserIdentificationSpec  extends FunSpec with Matchers {

  private def jsonFactory: JsonNodeFactory = JsonNodeFactory.instance
  private val crypto: Crypto = new BouncyCastleCrypto()

  private object Defaults {

    val rpId = RelyingPartyIdentity.builder().name("Test party").id("localhost").build()

    // These values were generated using TestAuthenticator.makeCredentialExample(TestAuthenticator.createCredential())
    val authenticatorData: ArrayBuffer = BinaryUtil.fromHex("49960de5880e8c687434170f6476605b8fe4aeb9a28632c7995cf3ba831d97630100000539").toVector
    val clientDataJson: String = """{"challenge":"AAEBAgMFCA0VIjdZEGl5Yls","origin":"localhost","hashAlgorithm":"SHA-256","type":"webauthn.get","tokenBinding":{"status":"supported"}}"""
    val credentialId: ArrayBuffer = U2fB64Encoding.decode("aqFjEQkzH8I55SnmIyNM632MsPI_qZ60aGTSHZMwcKY").toVector
    val credentialKey: KeyPair = TestAuthenticator.importEcKeypair(
      privateBytes = BinaryUtil.fromHex("308193020100301306072a8648ce3d020106082a8648ce3d0301070479307702010104206a88f478910df685bc0cfcc2077e64fb3a8ba770fb23fbbcd1f6572ce35cf360a00a06082a8648ce3d030107a14403420004d8020a2ec718c2c595bb890fcdaf9b81cc742118efdbb8812ac4a9dd5ace2990ec22a48faf1544df0fe5fe0e2e7a69720e63a83d7f46aa022f1323eaf7967762").toVector,
      publicBytes = BinaryUtil.fromHex("3059301306072a8648ce3d020106082a8648ce3d03010703420004d8020a2ec718c2c595bb890fcdaf9b81cc742118efdbb8812ac4a9dd5ace2990ec22a48faf1544df0fe5fe0e2e7a69720e63a83d7f46aa022f1323eaf7967762").toVector
    )
    val signature: ArrayBuffer = BinaryUtil.fromHex("30450221008d478e4c24894d261c7fd3790363ba9687facf4dd1d59610933a2c292cffc3d902205069264c167833d239d6af4c7bf7326c4883fb8c3517a2c86318aa3060d8b441").toVector

    // These values are not signed over
    val userHandle: ArrayBuffer = BinaryUtil.fromHex("6d8972d9603ce4f3fa5d520ce6d024bf").toVector

    // These values are defined by the attestationObject and clientDataJson above
    val clientData = new CollectedClientData(WebAuthnCodecs.json.readTree(clientDataJson))
    val clientDataJsonBytes: ArrayBuffer = clientDataJson.getBytes("UTF-8").toVector
    val challenge: ArrayBuffer = clientData.getChallenge.toVector
    val requestedExtensions: Option[AuthenticationExtensionsClientInputs] = None
    val clientExtensionResults: AuthenticationExtensionsClientInputs = jsonFactory.objectNode()

    val request = PublicKeyCredentialRequestOptions.builder()
      .challenge(challenge.toArray)
      .rpId(Some(rpId.getId).asJava)
      .build()

    val publicKeyCredential: PublicKeyCredential[AuthenticatorAssertionResponse] = new PublicKeyCredential(
      U2fB64Encoding.encode(credentialId.toArray),
      new AuthenticatorAssertionResponse(
        U2fB64Encoding.encode(authenticatorData.toArray),
        U2fB64Encoding.encode(clientDataJsonBytes.toArray),
        U2fB64Encoding.encode(signature.toArray),
        null
      ),
      jsonFactory.objectNode()
    )
    val username = "foo-user"

    def defaultResponse(
      userHandle: Option[ArrayBuffer] = None
    ): AuthenticatorAssertionResponse = new AuthenticatorAssertionResponse(
      U2fB64Encoding.encode(authenticatorData.toArray),
      U2fB64Encoding.encode(clientDataJsonBytes.toArray),
      U2fB64Encoding.encode(signature.toArray),
      userHandle.map(_.toArray).map(U2fB64Encoding.encode).orNull
    )

    def defaultPublicKeyCredential(
      credentialId: ArrayBuffer = Defaults.credentialId,
      response: Option[AuthenticatorAssertionResponse] = None,
      userHandle: Option[ArrayBuffer] = None
    ): PublicKeyCredential[AuthenticatorAssertionResponse] =
      new PublicKeyCredential(
        U2fB64Encoding.encode(credentialId.toArray),
        response getOrElse defaultResponse(userHandle = userHandle),
        jsonFactory.objectNode()
      )
  }

  describe("The assertion ceremony") {

    val rp = RelyingParty.builder()
      .allowUntrustedAttestation(false)
      .challengeGenerator(new ChallengeGenerator() { override def generateChallenge(): Array[Byte] = Defaults.challenge.toArray })
      .origins(List(Defaults.rpId.getId).asJava)
      .preferredPubkeyParams(Nil.asJava)
      .rp(Defaults.rpId)
      .credentialRepository(new CredentialRepository {
        override def getCredentialIdsForUsername(username: String): java.util.List[PublicKeyCredentialDescriptor] =
          if (username == Defaults.username)
            List(new PublicKeyCredentialDescriptor(Defaults.credentialId.toArray)).asJava
          else
            Nil.asJava

        override def lookup(credId: Base64UrlString, lookupUserHandle: Base64UrlString) =
          if (credId == U2fB64Encoding.encode(Defaults.credentialId.toArray))
            Some(new RegisteredCredential(
              Defaults.credentialId.toArray,
              Defaults.userHandle.toArray,
              Defaults.credentialKey.getPublic,
              0
            )).asJava
          else
            None.asJava

        override def lookupAll(credId: Base64UrlString) = ???
        override def getUserHandleForUsername(username: String): Optional[Base64UrlString] =
          if (username == Defaults.username)
            Some(U2fB64Encoding.encode(Defaults.userHandle.toArray)).asJava
          else
            None.asJava
        override def getUsernameForUserHandle(userHandle: Base64UrlString): Optional[String] =
          if (userHandle == U2fB64Encoding.encode(Defaults.userHandle.toArray))
            Some(Defaults.username).asJava
          else
            None.asJava
      })
      .validateSignatureCounter(true)
      .build()

    it("succeeds for the default test case if a username was given.") {
      val request = rp.startAssertion(Some(Defaults.username).asJava, None.asJava, None.asJava)
      val result = Try(rp.finishAssertion(
        request,
        Defaults.publicKeyCredential,
        None.asJava
      ))

      result shouldBe a [Success[_]]
    }

    it("succeeds if username was not given but userHandle was returned.") {
      val request = rp.startAssertion(None.asJava, None.asJava, None.asJava)

      val response: PublicKeyCredential[AuthenticatorAssertionResponse] = Defaults.defaultPublicKeyCredential(
        userHandle = Some(Defaults.userHandle)
      )

      val result = Try(rp.finishAssertion(
        request,
        response,
        None.asJava
      ))

      result shouldBe a [Success[_]]
    }

    it("fails for the default test case if no username was given and no userHandle returned.") {
      val request = rp.startAssertion(None.asJava, None.asJava, None.asJava)
      val result = Try(rp.finishAssertion(
        request,
        Defaults.publicKeyCredential,
        None.asJava
      ))

      result shouldBe a [Failure[_]]
    }

  }

}
