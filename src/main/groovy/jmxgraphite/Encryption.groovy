package jmxgraphite

import java.security.Key
import java.security.spec.KeySpec
import java.security.AlgorithmParameters
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import org.apache.commons.codec.binary.Base64
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class Encryption {
	private static final Logger LOG = LoggerFactory.getLogger(JMXGraphite)
	
	static final byte[] salt = [ 0xF, 0x1, 0xA, 0x0, 0xB, 0x3, 0xC, 0xF ]
    static final byte[] KEY = [ -115, 84, 27, -75, -68, 23, 50, 87, 21, 90, 93, 74, 37, -82, 105, -52 ]
	static final byte[] IV = [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ]
	
	static file = 'default'
	static pass = null
	
	static Encrypt(password) {
		/*SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
		KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256)
		SecretKey tmp = factory.generateSecret(spec)
		println tmp.getEncoded()*/
		
		
		IvParameterSpec ivspec = new IvParameterSpec(IV)
		SecretKey secret = new SecretKeySpec(KEY, 'AES')
		
		Cipher cipher = Cipher.getInstance('AES/CBC/PKCS5Padding')
		cipher.init(Cipher.ENCRYPT_MODE, secret, ivspec)
		AlgorithmParameters params = cipher.parameters
		
		new String(Base64.encodeBase64(cipher.doFinal(password.getBytes('UTF-8'))))
	}
	
	static Decrypt(password) {
		IvParameterSpec ivspec = new IvParameterSpec(IV)
		SecretKey secret = new SecretKeySpec(KEY, 'AES')
		
		Cipher cipher = Cipher.getInstance('AES/CBC/PKCS5Padding')
		cipher.init(Cipher.DECRYPT_MODE, secret, ivspec)
		AlgorithmParameters params = cipher.parameters
		
		new String(cipher.doFinal(Base64.decodeBase64(password.getBytes('UTF-8'))))
	}
}