# ecommerce

```bash
cd infrastructure

terraform init

# first create ECR repository
terraform apply \
-target=aws_ecr_repository.productdb \
-target=aws_ecr_repository.customerdb \
-target=aws_ecr_repository.shoppingcartdb \
-target=aws_ecr_repository.rabbitmq \
-target=aws_ecr_repository.product \
-target=aws_ecr_repository.credit_card \
-target=aws_ecr_repository.warehouse \
-target=aws_ecr_repository.shopping_cart


# give permisson to script files
chmod +x ./*.sh

# build and push images to ECR
./push_productdb_image.sh
./push_customerdb_image.sh
./push_shoppingcartdb_image.sh
./push_rabbitmq_image.sh
./push_product_image.sh
./push_credit_card_image.sh
./push_warehouse_image.sh
./push_shopping_cart_image.sh

# then create everything else
terraform apply

# remember to destroy when done
terraform destroy
```

To let the database work fine, we need to set the `peers` for our nodes. We can get the private IPs of the nodes from the AWS console.

The nodes has an endpoint `/config/peers` to set the peers list. We can use `curl` to set the peers for each node. For example, if we have five nodes with private IPs

```
172.31.73.175
172.31.26.167
172.31.45.54
172.31.54.120
172.31.95.30
```

we can set the peers as follows:

```bash
curl -X POST "{{db_baseUrl}}/config/peers" \
     -H "Content-Type: application/json" \
     -d '[
            "http://172.31.73.175:8080",
            "http://172.31.26.167:8080",
            "http://172.31.45.54:8080",
            "http://172.31.54.120:8080",
            "http://172.31.95.30:8080"
         ]'
```

The baseUrl can be replaced with the public IP of each node respectively or use the load balancer address to set the peers for all nodes at once. If using load balancer address, make sure to run the command multiple times until all nodes have their peers set.
