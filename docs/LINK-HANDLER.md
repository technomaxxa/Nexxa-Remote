# Windows `nexxa://` invitation links

Nexxa invitations use the `nexxa://` URI scheme. Registering the scheme allows Windows to open an invitation with Nexxa when a user clicks it.

## Register

1. Extract Nexxa to the folder where it will remain installed.
2. Start Nexxa once so the private Java runtime is provisioned.
3. Double-click `Register Nexxa Link Handler.bat`.
4. The script writes the protocol association under the current user's `HKCU\Software\Classes\nexxa` registry key.

The registered command points to the current folder's `runtime\bin\javaw.exe` and `NexxaRemote.jar`.

## Unregister

Double-click `Unregister Nexxa Link Handler.bat`. It removes the per-user `nexxa` protocol association.

## Moving or deleting Nexxa

Unregister the handler before moving or deleting the application folder. After moving it, run the registration script again from the new location. The handler is per-user and does not require a machine-wide protocol registration.

## Privacy

Invitation links contain a temporary session access key. Do not place real invitations in public webpages, documentation, issue reports, or repositories.
