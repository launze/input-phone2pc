using System;
using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

class Program
{
    static void Main()
    {
        string pfxPath = "server.pfx";
        string password = "password123";
        
        try
        {
            // 加载PFX文件
            X509Certificate2 cert = new X509Certificate2(pfxPath, password, X509KeyStorageFlags.Exportable | X509KeyStorageFlags.PersistKeySet);
            
            // 导出证书
            string certPem = "-----BEGIN CERTIFICATE-----\n" + 
                Convert.ToBase64String(cert.RawData, Base64FormattingOptions.InsertLineBreaks) + 
                "\n-----END CERTIFICATE-----";
            File.WriteAllText("cert.pem", certPem);
            Console.WriteLine("证书已导出到 cert.pem");
            
            // 导出私钥
            RSA rsa = cert.GetRSAPrivateKey();
            if (rsa != null)
            {
                byte[] keyBytes = rsa.ExportPkcs8PrivateKey();
                string keyPem = "-----BEGIN PRIVATE KEY-----\n" + 
                    Convert.ToBase64String(keyBytes, Base64FormattingOptions.InsertLineBreaks) + 
                    "\n-----END PRIVATE KEY-----";
                File.WriteAllText("key.pem", keyPem);
                Console.WriteLine("私钥已导出到 key.pem");
            }
            else
            {
                Console.WriteLine("无法获取RSA私钥");
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine("错误: " + ex.Message);
        }
    }
}