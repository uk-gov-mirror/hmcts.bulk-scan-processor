resource "azurerm_key_vault_secret" "test_storage_account_key" {
  name = "test-storage-account-key"
  value = "${azurerm_storage_account.provider.primary_access_key}"
  vault_uri = "${data.azurerm_key_vault.key_vault.vault_uri}"
}

data "azurerm_key_vault_secret" "source_test_s2s_secret" {
  name = "microservicekey-bulk-scan-processor-tests"
  vault_uri = "${local.s2s_vault_url}"
}

resource "azurerm_key_vault_secret" "test_s2s_secret" {
  name = "test-s2s-secret"
  value = "${data.azurerm_key_vault_secret.source_test_s2s_secret.value}"
  vault_uri = "${data.azurerm_key_vault.key_vault.vault_uri}"
}