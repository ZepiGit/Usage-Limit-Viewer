import Foundation

// The keychain surface the kit's `KeychainCredentialStore` uses, so its real Darwin-only
// source can be type-checked off a Mac. Nothing here stores anything.

// CoreFoundation does not exist here, and the kit's keychain code casts between these and
// Foundation types constantly (`kSecClass as String`, `attributes as CFDictionary`). Mapping
// them onto the Foundation classes keeps every one of those casts meaningful.
public typealias CFString = NSString
public typealias CFDictionary = NSDictionary
public typealias CFTypeRef = AnyObject

public typealias OSStatus = Int32

public let errSecSuccess: OSStatus = 0
public let errSecItemNotFound: OSStatus = -25300
public let errSecDuplicateItem: OSStatus = -25299
public let errSecProtectedDataUnavailable: OSStatus = -25308

public let kSecClass: CFString = "class" as CFString
public let kSecClassGenericPassword: CFString = "genp" as CFString
public let kSecAttrService: CFString = "svce" as CFString
public let kSecAttrAccount: CFString = "acct" as CFString
public let kSecValueData: CFString = "v_Data" as CFString
public let kSecReturnData: CFString = "r_Data" as CFString
public let kSecReturnAttributes: CFString = "r_Attributes" as CFString
public let kSecMatchLimit: CFString = "m_Limit" as CFString
public let kSecMatchLimitOne: CFString = "m_LimitOne" as CFString
public let kSecMatchLimitAll: CFString = "m_LimitAll" as CFString
public let kSecAttrAccessible: CFString = "pdmn" as CFString
public let kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly: CFString = "cku" as CFString

public func SecItemAdd(_ attributes: CFDictionary, _ result: UnsafeMutablePointer<CFTypeRef?>?) -> OSStatus {
    errSecSuccess
}

public func SecItemUpdate(_ query: CFDictionary, _ attributesToUpdate: CFDictionary) -> OSStatus {
    errSecSuccess
}

public func SecItemDelete(_ query: CFDictionary) -> OSStatus { errSecSuccess }

public func SecItemCopyMatching(_ query: CFDictionary, _ result: UnsafeMutablePointer<CFTypeRef?>?) -> OSStatus {
    errSecSuccess
}

public let errSecAllocate: OSStatus = -108

public struct SecRandomRef {}
public let kSecRandomDefault: SecRandomRef? = SecRandomRef()

public func SecRandomCopyBytes(_ rnd: SecRandomRef?, _ count: Int,
                               _ bytes: UnsafeMutableRawPointer) -> OSStatus {
    errSecSuccess
}
