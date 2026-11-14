/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts what a phone sends the TV over the home Wi-Fi (provider logins typed on the phone,
 * remote-control commands) so nobody else on the network can read or forge it.
 *
 * The 256-bit key is created on the TV and handed to the phone inside the QR code, in the URL
 * *fragment* (`…/#k=<hex>`). Browsers never send the fragment over the network, so the key only
 * ever exists on the TV screen and on the phone that scanned it.
 *
 * Browsers refuse WebCrypto on plain-http LAN pages, so the page brings its own small, audited-by-
 * test implementation ([JS]): AES-256-CTR for secrecy, HMAC-SHA256 over iv|ciphertext for
 * integrity (encrypt-then-MAC). Wire format: base64( iv(16) | ciphertext | mac(32) ).
 */
object LanCrypto {

    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun macKey(key: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("opentv-mac".toByteArray() + key)

    /** Decrypts and authenticates; null if the message was forged, damaged or used another key. */
    fun open(b64: String, key: ByteArray): String? = runCatching {
        val raw = Base64.decode(b64.replace(' ', '+'), Base64.DEFAULT)
        if (raw.size < 16 + 32) return null
        val iv = raw.copyOfRange(0, 16)
        val ct = raw.copyOfRange(16, raw.size - 32)
        val tag = raw.copyOfRange(raw.size - 32, raw.size)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey(key), "HmacSHA256")) }
        if (!MessageDigest.isEqual(mac.doFinal(iv + ct), tag)) return null
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(c.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    /** Encrypts a reply for the phone (same format). */
    fun seal(plain: String, key: ByteArray): String {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey(key), "HmacSHA256")) }
        return Base64.encodeToString(iv + ct + mac.doFinal(iv + ct), Base64.NO_WRAP)
    }

    /**
     * The browser half: `OTVC.seal(text)` → base64 using the key from `location.hash` (#k=hex).
     * Pure JS AES-256 (encrypt direction only — CTR needs nothing else), SHA-256 and HMAC.
     */
    const val JS = """
var OTVC=(function(){
var S=[],X=[],R=[1,2,4,8,16,32,64,128,27,54];(function(){var p=1,q=1;do{p=p^((p<<1)&255)^(p&128?27:0);q^=q<<1;q^=q<<2;q^=q<<4;q&=255;if(q&128)q^=9;var x=q^(q<<1|q>>7)&255^(q<<2|q>>6)&255^(q<<3|q>>5)&255^(q<<4|q>>4)&255^99;S[p]=x&255;}while(p!=1);S[0]=99;})();
function xt(a){return((a<<1)^(a&128?27:0))&255}
function expand(k){var n=k.length/4,w=[],i,t;for(i=0;i<n;i++)w[i]=[k[4*i],k[4*i+1],k[4*i+2],k[4*i+3]];for(i=n;i<4*(n+7);i++){t=w[i-1].slice();if(i%n==0){t=[S[t[1]]^R[i/n-1],S[t[2]],S[t[3]],S[t[0]]];}else if(n>6&&i%n==4){t=[S[t[0]],S[t[1]],S[t[2]],S[t[3]]];}w[i]=[w[i-n][0]^t[0],w[i-n][1]^t[1],w[i-n][2]^t[2],w[i-n][3]^t[3]];}return w}
function enc(w,b){var s=b.slice(),r,c,i,nr=w.length/4-1;for(c=0;c<4;c++)for(i=0;i<4;i++)s[4*c+i]^=w[c][i];for(r=1;r<=nr;r++){for(i=0;i<16;i++)s[i]=S[s[i]];var t=s.slice();for(c=0;c<4;c++)for(i=0;i<4;i++)s[4*c+i]=t[4*((c+i)%4)+i];if(r<nr){for(c=0;c<4;c++){var a0=s[4*c],a1=s[4*c+1],a2=s[4*c+2],a3=s[4*c+3];s[4*c]=xt(a0)^xt(a1)^a1^a2^a3;s[4*c+1]=a0^xt(a1)^xt(a2)^a2^a3;s[4*c+2]=a0^a1^xt(a2)^xt(a3)^a3;s[4*c+3]=xt(a0)^a0^a1^a2^xt(a3);}}for(c=0;c<4;c++)for(i=0;i<4;i++)s[4*c+i]^=w[4*r+c][i];}return s}
var K=[0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
function sha(m){m=m.slice();var l=m.length*8,h=[0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19],w=[],i,j;m.push(128);while(m.length%64!=56)m.push(0);for(i=7;i>=0;i--)m.push(i>3?0:(l>>>(8*i))&255);function r(x,n){return(x>>>n)|(x<<(32-n))}for(i=0;i<m.length;i+=64){for(j=0;j<16;j++)w[j]=(m[i+4*j]<<24)|(m[i+4*j+1]<<16)|(m[i+4*j+2]<<8)|m[i+4*j+3];for(j=16;j<64;j++){var s0=r(w[j-15],7)^r(w[j-15],18)^(w[j-15]>>>3),s1=r(w[j-2],17)^r(w[j-2],19)^(w[j-2]>>>10);w[j]=(w[j-16]+s0+w[j-7]+s1)|0;}var a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],k=h[7];for(j=0;j<64;j++){var t1=(k+(r(e,6)^r(e,11)^r(e,25))+((e&f)^(~e&g))+K[j]+w[j])|0,t2=((r(a,2)^r(a,13)^r(a,22))+((a&b)^(a&c)^(b&c)))|0;k=g;g=f;f=e;e=(d+t1)|0;d=c;c=b;b=a;a=(t1+t2)|0;}h[0]=(h[0]+a)|0;h[1]=(h[1]+b)|0;h[2]=(h[2]+c)|0;h[3]=(h[3]+d)|0;h[4]=(h[4]+e)|0;h[5]=(h[5]+f)|0;h[6]=(h[6]+g)|0;h[7]=(h[7]+k)|0;}var o=[];for(i=0;i<8;i++)o.push((h[i]>>>24)&255,(h[i]>>>16)&255,(h[i]>>>8)&255,h[i]&255);return o}
function hmac(k,m){if(k.length>64)k=sha(k);var i,o=[],p=[];for(i=0;i<64;i++){o[i]=(k[i]||0)^0x5c;p[i]=(k[i]||0)^0x36;}return sha(o.concat(sha(p.concat(m))))}
function utf8(s){return Array.prototype.slice.call(new TextEncoder().encode(s))}
function hx(h){var o=[];for(var i=0;i<h.length;i+=2)o.push(parseInt(h.substr(i,2),16));return o}
function key(){var m=/k=([0-9a-f]{64})/.exec(location.hash);if(m)try{sessionStorage.setItem("otvk",m[1])}catch(e){}var v=m?m[1]:(function(){try{return sessionStorage.getItem("otvk")}catch(e){return null}})();return v?hx(v):null}
function seal(text){var k=key();if(!k)throw new Error("no key");var w=expand(k),iv=Array.prototype.slice.call(crypto.getRandomValues(new Uint8Array(16))),pt=utf8(text),ct=[],ctr=iv.slice(),i,j;for(i=0;i<pt.length;i+=16){var ks=enc(w,ctr);for(j=0;j<16&&i+j<pt.length;j++)ct.push(pt[i+j]^ks[j]);for(j=15;j>=0;j--){ctr[j]=(ctr[j]+1)&255;if(ctr[j])break;}}var mk=sha(utf8("opentv-mac").concat(k)),tag=hmac(mk,iv.concat(ct)),all=iv.concat(ct,tag),s="";for(i=0;i<all.length;i++)s+=String.fromCharCode(all[i]);return btoa(s)}
return{seal:seal,hasKey:function(){return !!key()}};
})();
"""
}
