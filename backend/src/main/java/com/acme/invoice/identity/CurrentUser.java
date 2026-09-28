package com.acme.invoice.identity;

import java.util.UUID;

public interface CurrentUser {

    UUID requireId();
}
