## Run this service on AWS
```
cd shopping-cart-service
mvn spring-boot:run \
  -Dspring-boot.run.arguments="\
--customer.db.base-url=http://{AWS_customerDB_ALB}\
--shoppingCart.db.base-url=http://{AWS_shoppingCartDB_ALB}"

```

## Run this service locally
### 0. Run the databases
See `customerDB` and `shoppingCartDB` 

### 1. Run the Warehouse Service
(Change the server port to 8071)
See `warehouse-service/README.md` 

### 2. Run the Credit Card Auth Service. 
```
cd a3-CreditCard-service
mvn spring-boot:run
```

### 3. Run the Shopping Cart Service 
(Change the server port to 8072)
```
cd shopping-cart-service
mvn spring-boot:run
```

### 4. Test in Terminal:  
#### (1) Get a `cartId` ({customerId} can be any random string)  
`curl -X GET "http://localhost:8081/shopping-cart/by-customer/{customerId}"`  
#### (2) Add items to the shopping cart with the `cartId`  
`curl -X POST "http://localhost:8081/shopping-cart/{cartId}/items?itemId=SKU001&quantity=1"`
`curl -X POST "http://localhost:8081/shopping-cart/{cartId}/items?itemId=SKU002&quantity=3" `
#### (3) Checkout with the cartId  (`4111-1111-1111-1111` is a random credit card number)
`curl -X POST "http://localhost:8081/shopping-cart/{cartId}/checkout?creditCardNumber=4111-1111-1111-1111"`

### 5. Check the log in Console
In the CreditCardAuth service console:
```
23:42:44.723 [http-nio-8070-exec-1] INFO  c.c.a.c.CreditCardController - CCA AUTHORIZED ****-****-****-1111
```

In the Warehouse service Console:
```
Reserved product 1, quantity: 1
Reserved product 2, quantity: 3
Shipping product 1, quantity: 1
Shipping product 2, quantity: 3
```
